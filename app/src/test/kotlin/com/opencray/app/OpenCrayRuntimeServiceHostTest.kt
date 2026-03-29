package com.opencray.app

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import com.opencray.app.facade.mcp.EmptyMcpSettingsFacade
import com.opencray.app.facade.safety.EmptySafetySettingsFacade
import com.opencray.app.facade.skills.EmptySkillsFacade
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
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayRuntimeServiceHostTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun runtimeOwnerAccessProjectsOnlyHostFacingRuntimeDependencies() {
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeManager = NoOpAgentSessionRuntimeManager()
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      private val stores = linkedMapOf<String, SessionSupplementStore>()

      override fun forChatSession(sessionId: String): SessionSupplementStore =
        stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
    }
    val approvalRegistry = AgentTaskApprovalRegistry()
    val memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
      memoryStore = InMemoryMemoryStore(),
    )
    val replayAccess = OpenCrayRuntimeReplayAccess(
      approvalRejectionRecorder = { _, _, _, _, _, _ -> },
      approvalApprovedRecorder = { _, _, _, _, _, _ -> },
      subAgentReplayRecorder = { _, _ -> },
      runCancellationRecorder = { _, _, _, _, _ -> },
      terminalReplayRepairer = { _, _ -> },
    )
    val owner = InProcessOpenCrayRuntimeOwner(
      lifecycleDescriptor = lifecycleDescriptor,
      sessionRuntimeManager = runtimeManager,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      supplementStoreFactory = supplementStoreFactory,
      transcriptMessagesProvider = { emptyList() },
      approvalRegistry = approvalRegistry,
      memoryIngestionCoordinator = memoryIngestionCoordinator,
      replayAccess = replayAccess,
    )

    val access = owner.toRuntimeOwnerAccess()

    assertSame(lifecycleDescriptor, access.lifecycleDescriptor)
    assertSame(lifecycleDescriptor, access.hostAccess.lifecycleDescriptor)
    assertSame(memoryIngestionCoordinator, access.memoryIngestionCoordinator)
    assertSame(replayAccess, access.replayAccess)
  }

  @Test
  fun runtimeServiceWorkStateTrackerTransitionsBetweenIdleAndActiveWork() {
    var now = 1_000L
    var summary = RuntimeOwnerWorkSummary()
    val tracker = RuntimeServiceWorkStateTracker(
      workSummaryProvider = { summary },
      clock = { now },
    )

    val initial = tracker.currentState()
    assertEquals("idle", initial.phase)
    assertEquals(false, initial.hasActiveWork)
    assertEquals(true, initial.idleSinceEpochMs != null)
    assertEquals(null, initial.keepAliveReason)

    now = 2_000L
    summary = RuntimeOwnerWorkSummary(
      trackedSessionCount = 1,
      activeRunCount = 1,
      activeSessionIds = listOf("session-active"),
      pendingWorkSessionIds = listOf("session-active"),
    )
    val active = tracker.refresh()
    assertEquals("active_work", active.phase)
    assertEquals(true, active.hasActiveWork)
    assertEquals(1, active.activeRunCount)
    assertEquals(1, active.activeSessionCount)
    assertEquals(1, active.pendingWorkSessionCount)
    assertEquals(0, active.liveManagedProcessSessionCount)
    assertEquals(true, active.keepAliveRequired)
    assertEquals("active_run", active.keepAliveReason)
    assertEquals(2_000L, active.changedAtEpochMs)
    assertEquals(2_000L, active.activeSinceEpochMs)
    assertEquals(null, active.idleSinceEpochMs)

    now = 3_000L
    summary = RuntimeOwnerWorkSummary(
      trackedSessionCount = 1,
      activeRunCount = 1,
      activeSessionIds = listOf("session-active"),
      pendingWorkSessionIds = listOf("session-active"),
      liveManagedProcessSessionIds = listOf("session-active"),
    )
    val managedProcess = tracker.refresh()
    assertEquals("active_work", managedProcess.phase)
    assertEquals(1, managedProcess.liveManagedProcessSessionCount)
    assertEquals("managed_process", managedProcess.keepAliveReason)
    assertEquals(2_000L, managedProcess.activeSinceEpochMs)
    assertEquals(3_000L, managedProcess.changedAtEpochMs)

    now = 4_000L
    summary = RuntimeOwnerWorkSummary()
    val idle = tracker.refresh()
    assertEquals("idle", idle.phase)
    assertEquals(false, idle.hasActiveWork)
    assertEquals(false, idle.keepAliveRequired)
    assertEquals(null, idle.keepAliveReason)
    assertEquals(4_000L, idle.changedAtEpochMs)
    assertEquals(null, idle.activeSinceEpochMs)
    assertEquals(4_000L, idle.idleSinceEpochMs)
  }

  @Test
  fun runtimeServiceWorkStateTrackerRefreshesWhenActiveCountsChange() {
    var now = 1_000L
    var summary = RuntimeOwnerWorkSummary(
      trackedSessionCount = 1,
      activeRunCount = 1,
      activeSessionIds = listOf("session-a"),
      pendingWorkSessionIds = listOf("session-a"),
    )
    val tracker = RuntimeServiceWorkStateTracker(
      workSummaryProvider = { summary },
      clock = { now },
    )

    val initial = tracker.refresh()

    now = 1_500L
    summary = RuntimeOwnerWorkSummary(
      trackedSessionCount = 2,
      activeRunCount = 2,
      activeSessionIds = listOf("session-a", "session-b"),
      pendingWorkSessionIds = listOf("session-a", "session-b"),
    )
    val updated = tracker.refresh()

    assertEquals(1, initial.activeRunCount)
    assertEquals(1, initial.activeSessionCount)
    assertEquals(2, updated.activeRunCount)
    assertEquals(2, updated.activeSessionCount)
    assertEquals(2, updated.pendingWorkSessionCount)
    assertEquals(1_500L, updated.changedAtEpochMs)
  }

  @Test
  fun runtimeServiceKeepAliveControllerSchedulesIdleStopAfterGrace() {
    var now = 1_000L
    val scheduler = RecordingRuntimeServiceDelayScheduler()
    val requestedStopIds = mutableListOf<Int>()
    val controller = RuntimeServiceKeepAliveController(
      idleGraceMs = 500L,
      scheduler = scheduler,
      stopRequester = { startId ->
        requestedStopIds += startId
        true
      },
      clock = { now },
    )

    val idleGrace = controller.onStartCommand(startId = 7)

    assertEquals(RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE, idleGrace.phase)
    assertEquals(true, idleGrace.stopScheduled)
    assertEquals(1_500L, idleGrace.stopDeadlineEpochMs)
    assertEquals(emptyList<Int>(), requestedStopIds)

    now = 1_500L
    scheduler.runNext()
    val stopRequested = controller.currentState()

    assertEquals(listOf(7), requestedStopIds)
    assertEquals(RuntimeServiceKeepAliveState.PHASE_STOP_REQUESTED, stopRequested.phase)
    assertEquals(false, stopRequested.stopScheduled)
    assertEquals(1_500L, stopRequested.lastStopRequestAtEpochMs)
    assertEquals(true, stopRequested.lastStopSucceeded)
  }

  @Test
  fun runtimeServiceKeepAliveControllerCancelsIdleStopWhenWorkResumes() {
    var now = 2_000L
    val scheduler = RecordingRuntimeServiceDelayScheduler()
    val requestedStopIds = mutableListOf<Int>()
    val controller = RuntimeServiceKeepAliveController(
      idleGraceMs = 500L,
      scheduler = scheduler,
      stopRequester = { startId ->
        requestedStopIds += startId
        true
      },
      clock = { now },
    )

    controller.onStartCommand(startId = 9)
    now = 2_100L
    val activeState = controller.onWorkStateChanged(
      RuntimeServiceWorkState(
        phase = RuntimeServiceWorkState.PHASE_ACTIVE_WORK,
        hasActiveWork = true,
        keepAliveRequired = true,
        keepAliveReason = RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_RUN,
        changedAtEpochMs = now,
        activeSinceEpochMs = now,
        idleSinceEpochMs = null,
      ),
    )

    scheduler.runNext()

    assertEquals(RuntimeServiceKeepAliveState.PHASE_ACTIVE_WORK, activeState.phase)
    assertEquals(false, activeState.stopScheduled)
    assertEquals(emptyList<Int>(), requestedStopIds)
    assertEquals(RuntimeServiceKeepAliveState.PHASE_ACTIVE_WORK, controller.currentState().phase)
  }

  @Test
  fun runtimeServiceKeepAliveControllerTransitionsToDestroyedOnDestroy() {
    var now = 3_000L
    val scheduler = RecordingRuntimeServiceDelayScheduler()
    val controller = RuntimeServiceKeepAliveController(
      idleGraceMs = 500L,
      scheduler = scheduler,
      stopRequester = { true },
      clock = { now },
    )

    controller.onStartCommand(startId = 3)
    now = 3_100L
    val destroyed = controller.onDestroy()

    scheduler.runNext()

    assertEquals(RuntimeServiceKeepAliveState.PHASE_DESTROYED, destroyed.phase)
    assertEquals(false, destroyed.stopScheduled)
    assertEquals(null, destroyed.stopDeadlineEpochMs)
    assertEquals(RuntimeServiceKeepAliveState.PHASE_DESTROYED, controller.currentState().phase)
  }

  @Test
  fun runtimeForegroundControllerPromotesAndStopsForegroundBasedOnWorkState() {
    var now = 5_000L
    val adapter = RecordingRuntimeForegroundServiceAdapter()
    val controller = RuntimeForegroundController(
      serviceAdapter = adapter,
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { now },
    )

    val active = controller.onWorkStateChanged(
      RuntimeServiceWorkState(
        phase = RuntimeServiceWorkState.PHASE_ACTIVE_WORK,
        hasActiveWork = true,
        activeRunCount = 1,
        activeSessionCount = 1,
        pendingWorkSessionCount = 1,
        keepAliveRequired = true,
        keepAliveReason = RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_RUN,
        changedAtEpochMs = now,
        activeSinceEpochMs = now,
      ),
    )

    assertEquals(RuntimeForegroundState.PHASE_FOREGROUND, active.phase)
    assertEquals(true, active.notificationVisible)
    assertEquals(1, adapter.startedModels.size)
    assertEquals(0, adapter.stopCount)

    now = 5_500L
    val idle = controller.onWorkStateChanged(
      RuntimeServiceWorkState(
        phase = RuntimeServiceWorkState.PHASE_IDLE,
        hasActiveWork = false,
        keepAliveRequired = false,
        changedAtEpochMs = now,
        idleSinceEpochMs = now,
      ),
    )

    assertEquals(RuntimeForegroundState.PHASE_IDLE, idle.phase)
    assertEquals(false, idle.notificationVisible)
    assertEquals(1, adapter.startedModels.size)
    assertEquals(1, adapter.stopCount)
  }

  @Test
  fun runtimeForegroundControllerUpdatesForegroundWhenActiveCountsChange() {
    var now = 6_000L
    val adapter = RecordingRuntimeForegroundServiceAdapter()
    val controller = RuntimeForegroundController(
      serviceAdapter = adapter,
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { now },
    )

    controller.onWorkStateChanged(
      RuntimeServiceWorkState(
        phase = RuntimeServiceWorkState.PHASE_ACTIVE_WORK,
        hasActiveWork = true,
        activeRunCount = 1,
        activeSessionCount = 1,
        pendingWorkSessionCount = 1,
        keepAliveRequired = true,
        keepAliveReason = RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_RUN,
        changedAtEpochMs = now,
        activeSinceEpochMs = now,
      ),
    )

    now = 6_200L
    val updated = controller.onWorkStateChanged(
      RuntimeServiceWorkState(
        phase = RuntimeServiceWorkState.PHASE_ACTIVE_WORK,
        hasActiveWork = true,
        activeRunCount = 2,
        activeSessionCount = 2,
        pendingWorkSessionCount = 2,
        keepAliveRequired = true,
        keepAliveReason = RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_RUN,
        changedAtEpochMs = now,
        activeSinceEpochMs = 6_000L,
      ),
    )

    assertEquals(RuntimeForegroundState.PHASE_FOREGROUND, updated.phase)
    assertEquals(2, updated.activeRunCount)
    assertEquals(2, updated.activeSessionCount)
    assertEquals(2, adapter.startedModels.size)
    assertEquals(2, adapter.startedModels.last().activeRunCount)
    assertEquals(2, adapter.startedModels.last().activeSessionCount)
  }

  @Test
  fun bootstrapSessionsForRuntimeServiceHostResumesAndRepairsActiveSession() {
    val root = temporaryFolder.newFolder("service-host-bootstrap")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    runtimeManager.putHandle(RecordingAgentSessionHandle(activeSessionId, runtimeManager.resumedSessionIds))
    val repairCalls = mutableListOf<Pair<String, List<AgentRunSnapshot>>>()
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = lifecycleDescriptor,
      hostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = lifecycleDescriptor,
        sessionRuntimeManager = runtimeManager,
        runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory(),
        promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
        supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
          private val stores = linkedMapOf<String, SessionSupplementStore>()

          override fun forChatSession(sessionId: String): SessionSupplementStore =
            stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
        },
        approvalRegistry = AgentTaskApprovalRegistry(),
      ),
      transcriptMessagesProvider = { emptyList() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { sessionId, runs ->
          repairCalls += sessionId to runs
        },
      ),
    )

    val result = bootstrapSessionsForRuntimeServiceHost(chatSessionStore, runtimeAccess)

    assertEquals(listOf(activeSessionId), runtimeManager.resumedSessionIds)
    assertEquals(listOf(activeSessionId), result.scannedSessionIds)
    assertTrue(result.repairedSessionIds.isEmpty())
    assertTrue(repairCalls.isEmpty())
  }

  @Test
  fun bootstrapSessionsForRuntimeServiceHostAlsoResumesNonActiveSessionsWithRetainedWork() {
    val root = temporaryFolder.newFolder("service-host-bootstrap-multi-session")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val firstSessionId = chatSessionStore.loadState().activeSession.sessionId
    chatSessionStore.appendUserMessage(firstSessionId, "Keep the first session")
    val retainedWorkSessionId = chatSessionStore.createSession().activeSession.sessionId
    chatSessionStore.appendUserMessage(retainedWorkSessionId, "Keep the retained-work session")
    val idleSessionId = chatSessionStore.createSession().activeSession.sessionId
    chatSessionStore.selectSession(firstSessionId)

    val retainedRun = AgentRunSnapshot(
      sessionId = retainedWorkSessionId,
      runId = "run-retained",
      taskId = "task-retained",
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
      lifecycleState = QueueTaskLifecycleState.QUEUED,
      taskState = AgentTaskState.QUEUED,
    )
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    runtimeManager.putHandle(
      RecordingAgentSessionHandle(
        sessionId = firstSessionId,
        resumedSessionIds = runtimeManager.resumedSessionIds,
      ),
    )
    runtimeManager.putHandle(
      RecordingAgentSessionHandle(
        sessionId = retainedWorkSessionId,
        resumedSessionIds = runtimeManager.resumedSessionIds,
        runs = listOf(retainedRun),
        hasPendingWork = true,
      ),
    )
    runtimeManager.putHandle(
      RecordingAgentSessionHandle(
        sessionId = idleSessionId,
        resumedSessionIds = runtimeManager.resumedSessionIds,
      ),
    )
    val repairCalls = mutableListOf<Pair<String, List<AgentRunSnapshot>>>()
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = lifecycleDescriptor,
      hostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = lifecycleDescriptor,
        sessionRuntimeManager = runtimeManager,
        runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory(),
        promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
        supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
          private val stores = linkedMapOf<String, SessionSupplementStore>()

          override fun forChatSession(sessionId: String): SessionSupplementStore =
            stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
        },
        approvalRegistry = AgentTaskApprovalRegistry(),
      ),
      transcriptMessagesProvider = { emptyList() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { sessionId, runs ->
          repairCalls += sessionId to runs
        },
      ),
    )

    val result = bootstrapSessionsForRuntimeServiceHost(chatSessionStore, runtimeAccess)

    assertEquals(
      setOf(firstSessionId, retainedWorkSessionId),
      result.resumedSessionIds.toSet(),
    )
    assertEquals(
      setOf(firstSessionId, retainedWorkSessionId, idleSessionId),
      result.scannedSessionIds.toSet(),
    )
    assertEquals(listOf(retainedWorkSessionId), result.repairedSessionIds)
    assertEquals(retainedWorkSessionId, repairCalls.single().first)
    assertEquals(listOf(retainedRun), repairCalls.single().second)
  }

  @Test
  fun inProcessBridgeLoadsSnapshotFromHostProvider() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("in-process-bridge"))
    val bridge = InProcessOpenCrayRuntimeServiceBridge {
      OpenCrayRuntimeServiceHost(
        dependencies = expected.dependencies,
        runtimeAccess = expected.runtimeAccess,
        serviceLifecycle = expected.serviceLifecycle,
        serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
          expected.runtimeAccess.hostAccess::activeWorkSummary,
        ).apply { refresh() },
      )
    }

    val actual = bridge.loadSnapshot()

    assertSame(expected.dependencies, actual.dependencies)
    assertSame(expected.runtimeAccess, actual.runtimeAccess)
    assertSame(expected.serviceLifecycle, actual.serviceLifecycle)
    assertEquals(expected.serviceWorkState.phase, actual.serviceWorkState.phase)
    assertEquals(expected.serviceKeepAliveState.phase, actual.serviceKeepAliveState.phase)
    assertEquals(
      expected.serviceWorkState.keepAliveRequired,
      actual.serviceWorkState.keepAliveRequired,
    )
  }

  @Test
  fun existingBridgeLoadsSnapshotFromExistingHost() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("existing-bridge"))
    val bridge = ExistingOpenCrayRuntimeServiceBridge(
      hostProvider = {
        OpenCrayRuntimeServiceHost(
          dependencies = expected.dependencies,
          runtimeAccess = expected.runtimeAccess,
          serviceLifecycle = expected.serviceLifecycle,
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            expected.runtimeAccess.hostAccess::activeWorkSummary,
          ).apply { refresh() },
        )
      },
    )

    val actual = bridge.loadSnapshot()

    assertSame(expected.dependencies, actual.dependencies)
    assertSame(expected.runtimeAccess, actual.runtimeAccess)
    assertSame(expected.serviceLifecycle, actual.serviceLifecycle)
    assertEquals(expected.serviceWorkState.phase, actual.serviceWorkState.phase)
    assertEquals(expected.serviceKeepAliveState.phase, actual.serviceKeepAliveState.phase)
    assertEquals(
      expected.serviceWorkState.keepAliveRequired,
      actual.serviceWorkState.keepAliveRequired,
    )
  }

  @Test
  fun existingBridgeFailsExplicitlyWhenHostMissing() {
    val bridge = ExistingOpenCrayRuntimeServiceBridge(
      hostProvider = { null },
      missingHostMessage = "runtime host missing",
    )

    val failure = runCatching { bridge.loadSnapshot() }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertEquals("runtime host missing", failure?.message)
  }

  @Test
  fun binderBackedBridgeDelegatesToBinderAccess() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binder-bridge"))
    val binderAccess = object : OpenCrayRuntimeServiceBinderAccess {
      override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected
    }
    val bridge = BinderBackedOpenCrayRuntimeServiceBridge(binderAccess)

    val actual = bridge.loadSnapshot()

    assertSame(expected.dependencies, actual.dependencies)
    assertSame(expected.runtimeAccess, actual.runtimeAccess)
    assertSame(expected.serviceLifecycle, actual.serviceLifecycle)
  }

  @Test
  fun serviceClientLoadsBridgeSnapshotWithProjectedConnectionState() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("client-bridge"))
    val connectionState = RuntimeServiceConnectionState.inProcessFallback(
      serviceStartRequested = true,
    )
    val client = BridgeBackedOpenCrayRuntimeServiceClient(
      bridge = InProcessOpenCrayRuntimeServiceBridge {
        OpenCrayRuntimeServiceHost(
          dependencies = expected.dependencies,
          runtimeAccess = expected.runtimeAccess,
          serviceLifecycle = expected.serviceLifecycle,
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            expected.runtimeAccess.hostAccess::activeWorkSummary,
          ).apply { refresh() },
        )
      },
      connectionState = connectionState,
    )

    val actual = client.loadSnapshot()

    assertSame(expected.dependencies, actual.bridgeSnapshot.dependencies)
    assertSame(expected.runtimeAccess, actual.bridgeSnapshot.runtimeAccess)
    assertSame(expected.serviceLifecycle, actual.bridgeSnapshot.serviceLifecycle)
    assertEquals(expected.serviceWorkState.phase, actual.bridgeSnapshot.serviceWorkState.phase)
    assertEquals(
      expected.serviceKeepAliveState.phase,
      actual.bridgeSnapshot.serviceKeepAliveState.phase,
    )
    assertEquals(
      expected.serviceWorkState.keepAliveRequired,
      actual.bridgeSnapshot.serviceWorkState.keepAliveRequired,
    )
    assertSame(connectionState, actual.connectionState)
  }

  @Test
  fun androidBindingClientTransitionsFromFallbackBindingToBinderConnection() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client"))
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge {
        OpenCrayRuntimeServiceHost(
          dependencies = expected.dependencies,
          runtimeAccess = expected.runtimeAccess,
          serviceLifecycle = expected.serviceLifecycle,
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            expected.runtimeAccess.hostAccess::activeWorkSummary,
          ).apply { refresh() },
        )
      },
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    val initial = client.loadSnapshot()

    assertEquals(1, startRequestCount)
    assertEquals(1, bindingAdapter.bindCount)
    assertEquals("binding", initial.connectionState.phase)
    assertEquals("in_process", initial.connectionState.transport)
    assertEquals(true, initial.connectionState.serviceStartRequested)
    assertEquals(true, initial.connectionState.bindingRequested)
    assertFalse(initial.connectionState.binderAvailable)
    assertSame(expected.dependencies, initial.bridgeSnapshot.dependencies)

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected
      },
    )

    val connectedState = client.loadConnectionState()
    val connectedSnapshot = client.loadSnapshot()

    assertEquals("bound", connectedState.phase)
    assertEquals("binder", connectedState.transport)
    assertTrue(connectedState.bindingRequested)
    assertTrue(connectedState.binderAvailable)
    assertSame(expected.dependencies, connectedSnapshot.bridgeSnapshot.dependencies)
    assertSame(expected.runtimeAccess, connectedSnapshot.bridgeSnapshot.runtimeAccess)
    assertEquals(
      expected.serviceWorkState.phase,
      connectedSnapshot.bridgeSnapshot.serviceWorkState.phase,
    )
    assertEquals(
      expected.serviceWorkState.keepAliveRequired,
      connectedSnapshot.bridgeSnapshot.serviceWorkState.keepAliveRequired,
    )
  }

  @Test
  fun androidBindingClientExposesBinderChatRuntimeGatewayWhenConnected() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-chat-gateway"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge {
        OpenCrayRuntimeServiceHost(
          dependencies = expected.dependencies,
          runtimeAccess = expected.runtimeAccess,
          serviceLifecycle = expected.serviceLifecycle,
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            expected.runtimeAccess.hostAccess::activeWorkSummary,
          ).apply { refresh() },
        )
      },
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    assertEquals(null, client.loadChatRuntimeGateway())

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = binderGateway
      },
    )

    assertSame(binderGateway, client.loadChatRuntimeGateway())
  }

  @Test
  fun androidBindingClientExposesBinderShellGatewayWhenConnected() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-shell-gateway"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingShellGateway("binder")
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge {
        OpenCrayRuntimeServiceHost(
          dependencies = expected.dependencies,
          runtimeAccess = expected.runtimeAccess,
          serviceLifecycle = expected.serviceLifecycle,
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            expected.runtimeAccess.hostAccess::activeWorkSummary,
          ).apply { refresh() },
        )
      },
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    assertEquals(null, client.loadShellGateway())

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadShellGateway(): OpenCrayShellGateway = binderGateway
      },
    )

    assertSame(binderGateway, client.loadShellGateway())
  }

  @Test
  fun androidBindingClientPeekSnapshotDoesNotStartServiceOrBind() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-passive-peek"))
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge {
        OpenCrayRuntimeServiceHost(
          dependencies = expected.dependencies,
          runtimeAccess = expected.runtimeAccess,
          serviceLifecycle = expected.serviceLifecycle,
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            expected.runtimeAccess.hostAccess::activeWorkSummary,
          ).apply { refresh() },
        )
      },
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    val snapshot = client.peekSnapshot()

    assertSame(expected.dependencies, snapshot?.bridgeSnapshot?.dependencies)
    assertEquals(0, startRequestCount)
    assertEquals(0, bindingAdapter.bindCount)
    assertEquals("fallback", snapshot?.connectionState?.phase)
  }

  @Test
  fun androidBindingClientExposesBinderSkillsGatewayWhenConnected() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-skills-gateway"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingSkillsGateway("binder")
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge {
        OpenCrayRuntimeServiceHost(
          dependencies = expected.dependencies,
          runtimeAccess = expected.runtimeAccess,
          serviceLifecycle = expected.serviceLifecycle,
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            expected.runtimeAccess.hostAccess::activeWorkSummary,
          ).apply { refresh() },
        )
      },
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    assertEquals(null, client.loadSkillsGateway())

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadSkillsGateway(): OpenCraySkillsGateway = binderGateway
      },
    )

    assertSame(binderGateway, client.loadSkillsGateway())
  }

  @Test
  fun androidBindingClientExposesBinderSettingsGatewayWhenConnected() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-settings-gateway"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingSettingsGateway("binder")
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge {
        OpenCrayRuntimeServiceHost(
          dependencies = expected.dependencies,
          runtimeAccess = expected.runtimeAccess,
          serviceLifecycle = expected.serviceLifecycle,
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            expected.runtimeAccess.hostAccess::activeWorkSummary,
          ).apply { refresh() },
        )
      },
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    assertEquals(null, client.loadSettingsGateway())

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadSettingsGateway(): OpenCraySettingsGateway = binderGateway
      },
    )

    assertSame(binderGateway, client.loadSettingsGateway())
  }

  @Test
  fun androidBindingClientReleasesIdleBindingAfterTransientUse() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-idle-release"))
    val bindingAdapter = RecordingBindingAdapter()
    val releaseScheduler = RecordingRuntimeServiceDelayScheduler()
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge {
        OpenCrayRuntimeServiceHost(
          dependencies = expected.dependencies,
          runtimeAccess = expected.runtimeAccess,
          serviceLifecycle = expected.serviceLifecycle,
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            expected.runtimeAccess.hostAccess::activeWorkSummary,
          ).apply { refresh() },
        )
      },
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      bindingReleaseDelayMs = 250L,
      bindingReleaseScheduler = releaseScheduler,
      serviceIntentFactory = { Intent() },
    )

    val initial = client.loadSnapshot()
    assertEquals("binding", initial.connectionState.phase)
    assertEquals(0, bindingAdapter.unbindCount)

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected
      },
    )

    releaseScheduler.runNext()

    val released = client.loadConnectionState()

    assertEquals(1, bindingAdapter.unbindCount)
    assertEquals("fallback", released.phase)
    assertEquals("binder_idle_released", released.fallbackReason)
    assertFalse(released.bindingRequested)
    assertFalse(released.binderAvailable)
  }

  @Test
  fun androidBindingClientKeepsBindingWhileConnectionObserverIsActive() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-observer-retain"))
    val bindingAdapter = RecordingBindingAdapter()
    val releaseScheduler = RecordingRuntimeServiceDelayScheduler()
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge {
        OpenCrayRuntimeServiceHost(
          dependencies = expected.dependencies,
          runtimeAccess = expected.runtimeAccess,
          serviceLifecycle = expected.serviceLifecycle,
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            expected.runtimeAccess.hostAccess::activeWorkSummary,
          ).apply { refresh() },
        )
      },
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      bindingReleaseDelayMs = 250L,
      bindingReleaseScheduler = releaseScheduler,
      serviceIntentFactory = { Intent() },
    )

    val dispose = client.observeConnectionState { }
    assertEquals(1, bindingAdapter.bindCount)

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected
      },
    )

    releaseScheduler.runNext()

    assertEquals(0, bindingAdapter.unbindCount)
    assertEquals("bound", client.loadConnectionState().phase)

    dispose()
    releaseScheduler.runNext()

    assertEquals(1, bindingAdapter.unbindCount)
    assertEquals("fallback", client.loadConnectionState().phase)
  }

  @Test
  fun serviceBackedShellGatewayPrefersBinderForLoadsAndFallsBackWhenUnavailable() {
    val binderGateway = RecordingShellGateway("binder")
    val fallbackGateway = RecordingShellGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = binderGateway,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayShellGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals("binder-shell", gateway.loadShellSnapshot()["source"])

    serviceClient.currentShellGateway = null

    assertEquals("fallback-shell", gateway.loadShellSnapshot()["source"])
  }

  @Test
  fun serviceBackedShellGatewayObserversSwitchBetweenFallbackAndBinderGateways() {
    val binderGateway = RecordingShellGateway("binder")
    val fallbackGateway = RecordingShellGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayShellGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )
    val observedShellSources = mutableListOf<String?>()

    gateway.observeShell { snapshot ->
      observedShellSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-shell"), observedShellSources)

    serviceClient.currentShellGateway = binderGateway
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-shell", "binder-shell"), observedShellSources)

    serviceClient.currentShellGateway = null
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-shell", "binder-shell", "fallback-shell"), observedShellSources)
  }

  @Test
  fun serviceBackedShellGatewayDoesNotReemitFallbackSnapshotWhenGatewayDoesNotSwitch() {
    val fallbackGateway = RecordingShellGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayShellGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()

    gateway.observeShell { snapshot ->
      observedSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-shell"), observedSources)

    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.bindingPending())

    assertEquals(listOf("fallback-shell"), observedSources)
  }

  @Test
  fun serviceBackedShellGatewayObservationDoesNotStartOrBindRuntimeService() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("shell-gateway-passive-observe"))
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge {
        OpenCrayRuntimeServiceHost(
          dependencies = expected.dependencies,
          runtimeAccess = expected.runtimeAccess,
          serviceLifecycle = expected.serviceLifecycle,
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            expected.runtimeAccess.hostAccess::activeWorkSummary,
          ).apply { refresh() },
        )
      },
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )
    val gateway = ServiceBackedOpenCrayShellGateway(
      serviceClient = client,
      fallbackGateway = RecordingShellGateway("fallback"),
    )
    val observedSources = mutableListOf<String?>()

    gateway.observeShell { snapshot ->
      observedSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-shell"), observedSources)
    assertEquals(0, startRequestCount)
    assertEquals(0, bindingAdapter.bindCount)
    assertEquals("fallback", client.loadConnectionState().phase)
  }

  @Test
  fun androidBindingClientRebindsAfterServiceDisconnectWhileObserverIsActive() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-disconnect-rebind"))
    val bindingAdapter = RecordingBindingAdapter()
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge {
        OpenCrayRuntimeServiceHost(
          dependencies = expected.dependencies,
          runtimeAccess = expected.runtimeAccess,
          serviceLifecycle = expected.serviceLifecycle,
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            expected.runtimeAccess.hostAccess::activeWorkSummary,
          ).apply { refresh() },
        )
      },
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    val dispose = client.observeConnectionState { }

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected
      },
    )

    bindingAdapter.disconnect()

    assertEquals("binding", client.loadConnectionState().phase)
    assertTrue(client.loadConnectionState().bindingRequested)
    assertEquals(2, bindingAdapter.bindCount)

    dispose()
  }

  @Test
  fun projectionOnlyShellGatewayObserverPollsBridgeSnapshotUpdates() {
    var bridgeSnapshot = bridgeSnapshot(temporaryFolder.newFolder("projection-shell-bridge")).copy(
      serviceKeepAliveState = RuntimeServiceKeepAliveState(
        phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
        changedAtEpochMs = 1_000L,
      ),
    )
    val gateway = ProjectionOnlyOpenCrayShellGateway(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      localeTagProvider = { "en" },
      hostLabel = "HOST",
      hostSummary = "summary",
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      bridgeSnapshotProvider = { bridgeSnapshot },
      mainThreadPoster = ImmediateMainThreadPoster,
      pollIntervalMs = 25L,
    )
    val observedKeepAlivePhases = mutableListOf<String?>()

    val disposer = gateway.observeShell { snapshot ->
      val keepAliveState = snapshot["runtimeServiceKeepAliveState"] as? Map<*, *>
      observedKeepAlivePhases += keepAliveState?.get("phase") as String?
    }

    try {
      assertEquals(listOf("created"), observedKeepAlivePhases)

      bridgeSnapshot = bridgeSnapshot.copy(
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
  fun serviceBackedChatRuntimeGatewayAllowsFallbackLoadsButRequiresBinderForCommands() {
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = binderGateway,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals("binder-chat", gateway.loadChatSnapshot()["source"])
    gateway.submitChatMessage("through binder", emptyList())
    assertEquals("through binder", binderGateway.submittedText)
    assertEquals(null, fallbackGateway.submittedText)

    serviceClient.currentChatGateway = null

    assertEquals("fallback-chat", gateway.loadChatSnapshot()["source"])
    val failure = runCatching {
      gateway.submitChatMessage("through fallback", emptyList())
    }.exceptionOrNull()
    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message?.contains("submitChatMessage") == true)
    assertTrue(failure?.message?.contains("phase=fallback") == true)
    assertEquals(null, fallbackGateway.submittedText)
  }

  @Test
  fun serviceBackedGatewayObserversSwitchBetweenFallbackAndBinderGateways() {
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )
    val observedChatSources = mutableListOf<String?>()
    val observedRuntimeSources = mutableListOf<String?>()

    gateway.observeChat { snapshot ->
      observedChatSources += snapshot["source"] as String?
    }
    gateway.observeChatRuntime { snapshot ->
      observedRuntimeSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-chat"), observedChatSources)
    assertEquals(listOf("fallback-runtime"), observedRuntimeSources)

    serviceClient.currentChatGateway = binderGateway
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-chat", "binder-chat"), observedChatSources)
    assertEquals(listOf("fallback-runtime", "binder-runtime"), observedRuntimeSources)

    serviceClient.currentChatGateway = null
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-chat", "binder-chat", "fallback-chat"), observedChatSources)
    assertEquals(
      listOf("fallback-runtime", "binder-runtime", "fallback-runtime"),
      observedRuntimeSources,
    )
  }

  @Test
  fun serviceBackedChatRuntimeGatewayWaitsForPendingBinderBeforeSubmittingMessage() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("chat-gateway-await-binder"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        fallbackBridge = InProcessOpenCrayRuntimeServiceBridge {
          OpenCrayRuntimeServiceHost(
            dependencies = expected.dependencies,
            runtimeAccess = expected.runtimeAccess,
            serviceLifecycle = expected.serviceLifecycle,
            serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
              expected.runtimeAccess.hostAccess::activeWorkSummary,
            ).apply { refresh() },
          )
        },
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = RecordingChatRuntimeGateway("fallback"),
    )
    var result: Map<String, Any?>? = null
    var failure: Throwable? = null

    val worker = Thread {
      runCatching {
        gateway.submitChatMessage(
          text = "hello",
          attachments = emptyList(),
        )
      }.onSuccess { payload ->
        result = payload
      }.onFailure { throwable ->
        failure = throwable
      }
    }

    worker.start()
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = binderGateway
      },
    )
    worker.join(1_000L)

    assertFalse(worker.isAlive)
    assertEquals(null, failure)
    assertEquals("hello", binderGateway.submittedText)
    assertEquals("binder-submit", result?.get("source"))
  }

  @Test
  fun serviceBackedSkillsGatewayAllowsFallbackLoadsButRequiresBinderForCommands() {
    val binderGateway = RecordingSkillsGateway("binder")
    val fallbackGateway = RecordingSkillsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = binderGateway,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals(
      "binder-skills",
      gateway.loadSkillsSnapshot(query = "", suggestedLimit = 0)["source"],
    )
    assertEquals(
      "Installed roin-orca/skills via binder.",
      gateway.installSkillSource(sourceRef = "roin-orca/skills", selectedSkillName = ""),
    )
    assertEquals("roin-orca/skills", binderGateway.lastInstalledSourceRef)
    assertEquals(null, fallbackGateway.lastInstalledSourceRef)

    serviceClient.currentSkillsGateway = null

    assertEquals(
      "fallback-skills",
      gateway.loadSkillsSnapshot(query = "", suggestedLimit = 0)["source"],
    )
    val failure = runCatching {
      gateway.installSkillSource(sourceRef = "fallback/skills", selectedSkillName = "")
    }.exceptionOrNull()
    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message?.contains("installSkillSource") == true)
    assertTrue(failure?.message?.contains("phase=fallback") == true)
    assertEquals(null, fallbackGateway.lastInstalledSourceRef)
  }

  @Test
  fun serviceBackedSkillsGatewayObserversSwitchBetweenFallbackAndBinderGateways() {
    val binderGateway = RecordingSkillsGateway("binder")
    val fallbackGateway = RecordingSkillsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()

    gateway.observeSkills { snapshot ->
      observedSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-skills"), observedSources)

    serviceClient.currentSkillsGateway = binderGateway
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-skills", "binder-skills"), observedSources)

    serviceClient.currentSkillsGateway = null
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-skills", "binder-skills", "fallback-skills"), observedSources)
  }

  @Test
  fun serviceBackedSkillsGatewayObserverRechecksGatewayAfterConnectionObservationStarts() {
    val binderGateway = RecordingSkillsGateway("binder")
    val fallbackGateway = RecordingSkillsGateway("fallback")
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = SkillsGatewayAvailableOnObserveClient(binderGateway),
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()

    gateway.observeSkills { snapshot ->
      observedSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-skills", "binder-skills"), observedSources)
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
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val activeRuns = runtimeSnapshot["activeRuns"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val events = runtimeSnapshot["events"] as List<Map<String, Any?>>
    val runSnapshot = gateway.loadChatRunSnapshot(runId)
    val chatSnapshot = gateway.loadChatSnapshot()
    @Suppress("UNCHECKED_CAST")
    val messages = chatSnapshot["messages"] as List<Map<String, Any?>>

    assertEquals(sessionId, runtimeSnapshot["sessionId"])
    assertEquals(1, activeRuns.size)
    assertEquals(runId, activeRuns.single()["runId"])
    assertEquals("running", activeRuns.single()["lifecycleState"])
    assertEquals(1, events.size)
    assertEquals("assistant_phase", events.single()["kind"])
    assertEquals("commentary", events.single()["phase"])
    assertEquals("Inspecting workspace", events.single()["text"])
    assertNotNull(runSnapshot)
    assertEquals(runId, runSnapshot?.get("runId"))
    assertEquals("running", runSnapshot?.get("lifecycleState"))
    assertEquals(1, messages.size)
    assertEquals("hello from user", messages.single()["text"])
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

  @Test
  fun serviceBackedSettingsGatewayAllowsFallbackLoadsButRequiresBinderForCommands() {
    val binderGateway = RecordingSettingsGateway("binder")
    val fallbackGateway = RecordingSettingsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = binderGateway,
    )
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals("binder-settings", gateway.loadSettingsOverview()["source"])
    assertEquals("binder-notification-settings", gateway.loadNotificationSettings()["source"])
    assertEquals("binder-sandbox-settings", gateway.loadSandboxSettings()["source"])
    assertEquals(
      "binder-notification-settings-save",
      gateway.saveNotificationSettings(
        mapOf(
          "masterEnabled" to false,
          "defaultDeliveryModeId" to "all",
        ),
      )["source"],
    )
    assertEquals(
      "binder-sandbox-settings-save",
      gateway.saveSandboxSettings(
        mapOf(
          "enabled" to true,
          "defaultBackend" to "sandbox",
        ),
      )["source"],
    )
    assertEquals(true, gateway.setMcpMasterEnabled(true)["enabled"])
    assertEquals(true, binderGateway.lastMcpMasterEnabled)
    assertEquals("all", binderGateway.lastNotificationSettingsPayload?.get("defaultDeliveryModeId"))
    assertEquals("sandbox", binderGateway.lastSandboxSettingsPayload?.get("defaultBackend"))
    assertEquals(null, fallbackGateway.lastMcpMasterEnabled)

    serviceClient.currentSettingsGateway = null

    assertEquals("fallback-settings", gateway.loadSettingsOverview()["source"])
    assertEquals("fallback-notification-settings", gateway.loadNotificationSettings()["source"])
    assertEquals("fallback-sandbox-settings", gateway.loadSandboxSettings()["source"])
    val notificationFailure = runCatching {
      gateway.saveNotificationSettings(mapOf("masterEnabled" to true))
    }.exceptionOrNull()
    val sandboxFailure = runCatching {
      gateway.saveSandboxSettings(mapOf("enabled" to true))
    }.exceptionOrNull()
    val failure = runCatching {
      gateway.setMcpMasterEnabled(false)
    }.exceptionOrNull()
    assertTrue(notificationFailure is IllegalStateException)
    assertTrue(notificationFailure?.message?.contains("saveNotificationSettings") == true)
    assertTrue(sandboxFailure is IllegalStateException)
    assertTrue(sandboxFailure?.message?.contains("saveSandboxSettings") == true)
    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message?.contains("setMcpMasterEnabled") == true)
    assertTrue(failure?.message?.contains("phase=fallback") == true)
    assertEquals(null, fallbackGateway.lastNotificationSettingsPayload)
    assertEquals(null, fallbackGateway.lastSandboxSettingsPayload)
    assertEquals(null, fallbackGateway.lastMcpMasterEnabled)
  }

  @Test
  fun serviceBackedSettingsGatewayObserversSwitchBetweenFallbackAndBinderGateways() {
    val binderGateway = RecordingSettingsGateway("binder")
    val fallbackGateway = RecordingSettingsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()

    gateway.observeSettingsOverview { snapshot ->
      observedSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-settings"), observedSources)

    serviceClient.currentSettingsGateway = binderGateway
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-settings", "binder-settings"), observedSources)

    serviceClient.currentSettingsGateway = null
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-settings", "binder-settings", "fallback-settings"), observedSources)
  }

  @Test
  fun serviceBackedSettingsGatewayAllowsFallbackStrongBackgroundLoadsAndActions() {
    val binderGateway = RecordingSettingsGateway("binder")
    val fallbackGateway = RecordingSettingsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals("fallback-strong-background", gateway.loadStrongBackgroundSnapshot()["source"])
    assertEquals(
      "fallback-strong-background-action",
      gateway.performStrongBackgroundAction(
        StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      )["source"],
    )
    assertEquals(
      StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      fallbackGateway.lastStrongBackgroundActionId,
    )

    serviceClient.currentSettingsGateway = binderGateway

    assertEquals("binder-strong-background", gateway.loadStrongBackgroundSnapshot()["source"])
    assertEquals(
      "binder-strong-background-action",
      gateway.performStrongBackgroundAction(
        StrongBackgroundActionIds.OPEN_EXACT_ALARM_SETTINGS,
      )["source"],
    )
    assertEquals(
      StrongBackgroundActionIds.OPEN_EXACT_ALARM_SETTINGS,
      binderGateway.lastStrongBackgroundActionId,
    )
  }

  @Test
  fun serviceBackedSettingsGatewayWaitsForPendingBinderBeforeSavingCustomProvider() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("settings-gateway-await-binder"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingSettingsGateway("binder")
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        fallbackBridge = InProcessOpenCrayRuntimeServiceBridge {
          OpenCrayRuntimeServiceHost(
            dependencies = expected.dependencies,
            runtimeAccess = expected.runtimeAccess,
            serviceLifecycle = expected.serviceLifecycle,
            serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
              expected.runtimeAccess.hostAccess::activeWorkSummary,
            ).apply { refresh() },
          )
        },
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = RecordingSettingsGateway("fallback"),
    )
    var result: Map<String, Any?>? = null
    var failure: Throwable? = null

    val worker = Thread {
      runCatching {
        gateway.saveCustomLlmProvider(
          selectedProviderOptionId = "provider-option",
          protocol = "responses",
          providerName = "Test Provider",
          providerNotes = "notes",
          baseUrl = "https://example.com",
          apiKey = "sk-test",
          model = "gpt-test",
          reasoningEffort = "medium",
          systemPrompt = "prompt",
        )
      }.onSuccess { payload ->
        result = payload
      }.onFailure { throwable ->
        failure = throwable
      }
    }

    worker.start()
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadSettingsGateway(): OpenCraySettingsGateway = binderGateway
      },
    )
    worker.join(1_000L)

    assertFalse(worker.isAlive)
    assertEquals(null, failure)
    assertEquals("binder-custom-llm", result?.get("source"))
  }

  private class NoOpAgentSessionRuntimeManager : AgentSessionRuntimeManager {
    override fun forSession(sessionId: String): AgentSessionHandle = error("unused in test")

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  private class RecordingAgentSessionRuntimeManager : AgentSessionRuntimeManager {
    private val handlesBySession = linkedMapOf<String, RecordingAgentSessionHandle>()
    val resumedSessionIds = mutableListOf<String>()

    fun putHandle(handle: RecordingAgentSessionHandle) {
      handlesBySession[handle.sessionId] = handle
    }

    override fun forSession(sessionId: String): AgentSessionHandle =
      handlesBySession.getOrPut(sessionId) {
        RecordingAgentSessionHandle(sessionId, resumedSessionIds)
      }

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  private class RecordingAgentSessionHandle(
    override val sessionId: String,
    private val resumedSessionIds: MutableList<String>,
    private val runs: List<AgentRunSnapshot> = emptyList(),
    private val hasPendingWork: Boolean = false,
    private val hasLiveManagedProcesses: Boolean = false,
  ) : AgentSessionHandle {
    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission = error("unused in test")

    override fun submitTask(task: AgentTask): AgentRunSubmission = error("unused in test")

    override fun ensureProcessing() = Unit

    override fun requestCancel(taskId: String): Boolean = false

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean = false

    override fun listRuns(): List<AgentRunSnapshot> = runs

    override fun findRun(runId: String): AgentRunSnapshot? = runs.firstOrNull { run -> run.runId == runId }

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState {
      resumedSessionIds += sessionId
      return SessionLifecycleState.IDLE
    }

    override fun snapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 1_000L,
      tasks = emptyList(),
    )

    override fun hasPendingWork(): Boolean = hasPendingWork

    override fun hasLiveManagedProcesses(): Boolean = hasLiveManagedProcesses
  }

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

  private class RecordingBindingAdapter : OpenCrayRuntimeServiceBindingAdapter {
    var bindCount: Int = 0
      private set
    var unbindCount: Int = 0
      private set
    private var connection: ServiceConnection? = null

    override fun bind(
      context: android.content.Context,
      intent: Intent,
      connection: ServiceConnection,
      flags: Int,
    ): Boolean {
      bindCount += 1
      this.connection = connection
      return true
    }

    override fun unbind(
      context: android.content.Context,
      connection: ServiceConnection,
    ) {
      if (this.connection === connection) {
        this.connection = null
      }
      unbindCount += 1
    }

    fun connect(binder: Binder) {
      checkNotNull(connection).onServiceConnected(
        ComponentName("org.opencray.app", "OpenCrayAgentRuntimeService"),
        binder,
      )
    }

    fun disconnect() {
      checkNotNull(connection).onServiceDisconnected(
        ComponentName("org.opencray.app", "OpenCrayAgentRuntimeService"),
      )
    }
  }

  private class RecordingRuntimeServiceDelayScheduler : RuntimeServiceDelayScheduler {
    private val tasks = ArrayDeque<RecordingDelayedTask>()

    override fun schedule(
      delayMs: Long,
      action: () -> Unit,
    ): RuntimeServiceDelayedTask {
      val task = RecordingDelayedTask(delayMs = delayMs, action = action)
      tasks += task
      return task
    }

    fun runNext() {
      while (tasks.isNotEmpty()) {
        val task = tasks.removeFirst()
        if (task.cancelled) {
          continue
        }
        task.action()
        return
      }
    }

    private class RecordingDelayedTask(
      val delayMs: Long,
      val action: () -> Unit,
    ) : RuntimeServiceDelayedTask {
      var cancelled: Boolean = false

      override fun cancel() {
        cancelled = true
      }
    }
  }

  private class RecordingRuntimeForegroundServiceAdapter : RuntimeForegroundServiceAdapter {
    val startedModels = mutableListOf<RuntimeForegroundNotificationModel>()
    var stopCount: Int = 0
      private set

    override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) {
      startedModels += model
    }

    override fun stopForeground(removeNotification: Boolean) {
      if (removeNotification) {
        stopCount += 1
      }
    }
  }

  private class RecordingShellGateway(
    private val label: String,
  ) : OpenCrayShellGateway {
    override fun loadShellSnapshot(): Map<String, Any?> = mapOf("source" to "$label-shell")

    override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      listener(loadShellSnapshot())
      return { }
    }
  }

  private class RecordingRuntimeServiceClient(
    var currentShellGateway: OpenCrayShellGateway?,
    var currentChatGateway: OpenCrayChatRuntimeGateway?,
    var currentSkillsGateway: OpenCraySkillsGateway?,
    var currentSettingsGateway: OpenCraySettingsGateway?,
  ) : OpenCrayRuntimeServiceClient {
    private val listeners = linkedSetOf<(RuntimeServiceConnectionState) -> Unit>()
    private var currentConnectionState: RuntimeServiceConnectionState =
      RuntimeServiceConnectionState.inProcessFallback()

    override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot = error("unused in test")

    override fun loadConnectionState(): RuntimeServiceConnectionState = currentConnectionState

    override fun peekConnectionState(): RuntimeServiceConnectionState = currentConnectionState

    override fun loadShellGateway(): OpenCrayShellGateway? = currentShellGateway

    override fun peekShellGateway(): OpenCrayShellGateway? = currentShellGateway

    override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway? = currentChatGateway

    override fun peekChatRuntimeGateway(): OpenCrayChatRuntimeGateway? = currentChatGateway

    override fun loadSkillsGateway(): OpenCraySkillsGateway? = currentSkillsGateway

    override fun peekSkillsGateway(): OpenCraySkillsGateway? = currentSkillsGateway

    override fun loadSettingsGateway(): OpenCraySettingsGateway? = currentSettingsGateway

    override fun peekSettingsGateway(): OpenCraySettingsGateway? = currentSettingsGateway

    override fun observeConnectionState(listener: (RuntimeServiceConnectionState) -> Unit): () -> Unit {
      listeners += listener
      return { listeners -= listener }
    }

    override fun observePassiveConnectionState(
      listener: (RuntimeServiceConnectionState) -> Unit,
    ): () -> Unit = observeConnectionState(listener)

    fun emitConnectionStateChanged(state: RuntimeServiceConnectionState = RuntimeServiceConnectionState.binderConnected()) {
      currentConnectionState = state
      listeners.toList().forEach { listener -> listener(state) }
    }
  }

  private class SkillsGatewayAvailableOnObserveClient(
    private val binderGateway: OpenCraySkillsGateway,
  ) : OpenCrayRuntimeServiceClient {
    private var observing: Boolean = false

    override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot = error("unused in test")

    override fun loadConnectionState(): RuntimeServiceConnectionState =
      if (observing) {
        RuntimeServiceConnectionState.binderConnected()
      } else {
        RuntimeServiceConnectionState.bindingPending()
      }

    override fun loadSkillsGateway(): OpenCraySkillsGateway? =
      if (observing) binderGateway else null

    override fun peekSkillsGateway(): OpenCraySkillsGateway? =
      if (observing) binderGateway else null

    override fun observeConnectionState(listener: (RuntimeServiceConnectionState) -> Unit): () -> Unit {
      observing = true
      return { }
    }

    override fun observePassiveConnectionState(
      listener: (RuntimeServiceConnectionState) -> Unit,
    ): () -> Unit = observeConnectionState(listener)
  }

  private class RecordingChatRuntimeGateway(
    private val label: String,
  ) : OpenCrayChatRuntimeGateway {
    var submittedText: String? = null
      private set

    override fun loadChatSnapshot(): Map<String, Any?> = mapOf("source" to "$label-chat")

    override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      listener(loadChatSnapshot())
      return { }
    }

    override fun loadChatRuntimeSnapshot(): Map<String, Any?> = mapOf("source" to "$label-runtime")

    override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? = mapOf(
      "source" to "$label-run",
      "runId" to runId,
    )

    override fun waitForChatRun(runId: String, timeoutMs: Long): Map<String, Any?>? = mapOf(
      "source" to "$label-wait",
      "runId" to runId,
      "timeoutMs" to timeoutMs,
    )

    override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      listener(loadChatRuntimeSnapshot())
      return { }
    }

    override fun loadMemoryDebugSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadSoulDebugSnapshot(): Map<String, Any?> = emptyMap()

    override fun searchMemoryDebug(query: String, maxResults: Int, minScore: Int): Map<String, Any?> =
      emptyMap()

    override fun getMemoryDebugSlice(path: String, fromLine: Int?, lines: Int): Map<String, Any?> =
      emptyMap()

    override fun applyMemoryDebugAction(recordId: String, actionId: String): Map<String, Any?> =
      emptyMap()

    override fun createChatSession() = Unit

    override fun copyChatSession(sessionId: String) = Unit

    override fun deleteChatSession(sessionId: String) = Unit

    override fun selectChatSession(sessionId: String) = Unit

    override fun branchChatSessionFromMessage(sessionId: String, messageId: String) = Unit

    override fun deleteChatMessage(sessionId: String, messageId: String) = Unit

    override fun recallChatMessage(sessionId: String, messageId: String) = Unit

    override fun submitChatMessage(
      text: String,
      attachments: List<com.opencray.runtime.OpenCrayFinalAttachment>,
    ): Map<String, Any?>? {
      submittedText = text
      return mapOf("source" to "$label-submit", "submittedText" to text)
    }

    override fun approveChatApproval(taskIdOrRunId: String) = Unit

    override fun approveChatApprovalForSession(taskIdOrRunId: String) = Unit

    override fun rejectChatApproval(taskIdOrRunId: String) = Unit

    override fun interruptChatRun(taskIdOrRunId: String) = Unit

    override fun retryChatRun(taskIdOrRunId: String) = Unit
  }

  private class RecordingSkillsGateway(
    private val label: String,
  ) : OpenCraySkillsGateway {
    var lastInstalledSourceRef: String? = null
      private set

    override fun loadSkillsSnapshot(query: String, suggestedLimit: Int): Map<String, Any?> = mapOf(
      "source" to "$label-skills",
      "query" to query,
      "suggestedLimit" to suggestedLimit,
    )

    override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      listener(loadSkillsSnapshot(query = "", suggestedLimit = 0))
      return { }
    }

    override fun setSkillEnabled(skillId: String, enabled: Boolean) = Unit

    override fun installSuggestedSkill(skillId: String): String =
      installSkillSource(sourceRef = skillId, selectedSkillName = "")

    override fun installSkillSource(
      sourceRef: String,
      selectedSkillName: String,
    ): String {
      lastInstalledSourceRef = sourceRef
      return "Installed $sourceRef via $label."
    }

    override fun installSkillSourceBatch(
      sourceRef: String,
      selectedSkillNames: List<String>,
    ): String = "Installed ${selectedSkillNames.size} skills via $label."

    override fun inspectSkillSource(sourceRef: String): Map<String, Any?> =
      mapOf("source" to "$label-inspect", "sourceRef" to sourceRef)

    override fun deleteInstalledSkill(skillId: String): String = "Removed $skillId via $label."

    override fun refreshSkills(): String = "Refreshed via $label."

    override fun checkInstalledSkillUpdates(skillId: String): String = "Checked $skillId via $label."

    override fun updateInstalledSkill(skillId: String): String = "Updated $skillId via $label."

    override fun loadSkillInstructions(skillId: String): Map<String, Any?> =
      mapOf("source" to "$label-instructions", "skillId" to skillId)

    override fun loadSuggestedSkillInstructions(
      sourceRef: String,
      selectedSkillName: String,
    ): Map<String, Any?> = mapOf(
      "source" to "$label-suggested-instructions",
      "sourceRef" to sourceRef,
      "selectedSkillName" to selectedSkillName,
    )

    override fun activateSkillsInstallSource(sourceId: String): String = sourceId
  }

  private class RecordingProjectionSkillsFacade : com.opencray.app.facade.skills.SkillsFacade {
    val loadQueries = mutableListOf<String>()
    val loadSuggestedLimits = mutableListOf<Int>()

    override fun loadSnapshot(
      query: String,
      suggestedLimit: Int,
    ): com.opencray.app.facade.skills.SkillsSnapshot {
      loadQueries += query
      loadSuggestedLimits += suggestedLimit
      val suggestions = listOf(
        com.opencray.app.facade.skills.SuggestedSkillSnapshot(
          id = "voice-notes",
          name = "voice-notes",
          description = "Capture voice notes into the workspace",
          sourceRef = "voice-notes",
          sourceLabel = "Local catalog",
        ),
        com.opencray.app.facade.skills.SuggestedSkillSnapshot(
          id = "git-sync",
          name = "git-sync",
          description = "Synchronize git branches",
          sourceRef = "git-sync",
          sourceLabel = "Local catalog",
        ),
      ).filter { item ->
        query.isBlank() ||
          item.name.contains(query, ignoreCase = true) ||
          item.description.contains(query, ignoreCase = true)
      }
      return com.opencray.app.facade.skills.SkillsSnapshot(
        installedSkills = listOf(
          com.opencray.app.facade.skills.InstalledSkillSnapshot(
            id = "installed-skill",
            name = "installed-skill",
            description = "Installed skill",
            isEnabled = true,
            sourceDirectoryPath = "/skills/installed-skill",
            canDelete = true,
          ),
        ),
        installSources = listOf(
          com.opencray.app.facade.skills.InstallSourceSnapshot(
            id = "curated-library",
            title = "Curated",
            subtitle = "Local catalog",
            actionLabel = "Inspect",
            isAvailable = true,
          ),
        ),
        suggestedSkills = suggestions,
      )
    }

    override fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean = false

    override fun installSkillSource(
      sourceRef: String,
      selectedSkillName: String,
    ): com.opencray.app.facade.skills.SkillInstallRequestResult =
      com.opencray.app.facade.skills.SkillInstallRequestResult(errorMessage = "unused in test")

    override fun installSuggestedSkill(skillId: String): Boolean = false

    override fun installSkillSourceBatch(
      sourceRef: String,
      selectedSkillNames: List<String>,
    ): com.opencray.runtime.skills.SkillPackageBatchInstallAttempt =
      com.opencray.runtime.skills.SkillPackageBatchInstallAttempt(
        errorCode = "UNUSED",
        errorMessage = "unused in test",
      )

    override fun inspectSkillSource(
      sourceRef: String,
    ): com.opencray.runtime.skills.SkillSourceInspectionAttempt =
      com.opencray.runtime.skills.SkillSourceInspectionAttempt(
        errorCode = "UNUSED",
        errorMessage = "unused in test",
      )

    override fun deleteInstalledSkill(skillId: String): Boolean = false

    override fun refresh() = Unit

    override fun checkInstalledSkillUpdates(
      skillId: String,
    ): com.opencray.runtime.skills.SkillPackageCheckReport =
      com.opencray.runtime.skills.SkillPackageCheckReport(results = emptyList())

    override fun updateInstalledSkill(
      skillId: String,
    ): com.opencray.runtime.skills.SkillPackageUpdateReport =
      com.opencray.runtime.skills.SkillPackageUpdateReport(results = emptyList())

    override fun loadInstructions(skillId: String): com.opencray.app.facade.skills.SkillInstructionsSnapshot? =
      com.opencray.app.facade.skills.SkillInstructionsSnapshot(
        id = skillId,
        name = skillId,
        description = "Instructions for $skillId",
        body = "# $skillId",
        sourceDirectoryPath = "/skills/$skillId",
        isEnabled = true,
        canDelete = true,
      )

    override fun loadSuggestedInstructions(
      sourceRef: String,
      selectedSkillName: String,
    ): com.opencray.app.facade.skills.SkillInstructionsSnapshot? =
      com.opencray.app.facade.skills.SkillInstructionsSnapshot(
        id = selectedSkillName.ifBlank { sourceRef },
        name = selectedSkillName.ifBlank { sourceRef },
        description = "Suggested instructions for ${selectedSkillName.ifBlank { sourceRef }}",
        body = "# ${selectedSkillName.ifBlank { sourceRef }}",
        sourceDirectoryPath = "/skills/${selectedSkillName.ifBlank { sourceRef }}",
        isEnabled = false,
        canDelete = false,
      )

    override fun enabledSkillRoots(): List<java.io.File> = emptyList()

    override fun activateInstallSource(sourceId: String): String = sourceId
  }

  private class RecordingSettingsGateway(
    private val label: String,
  ) : OpenCraySettingsGateway {
    var lastMcpMasterEnabled: Boolean? = null
      private set
    var lastNotificationSettingsPayload: Map<String, Any?>? = null
      private set
    var lastSandboxSettingsPayload: Map<String, Any?>? = null
      private set
    var lastStrongBackgroundActionId: String? = null
      private set

    override fun loadSettingsOverview(): Map<String, Any?> = mapOf("source" to "$label-settings")

    override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      listener(loadSettingsOverview())
      return { }
    }

    override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> =
      mapOf("source" to "$label-settings-detail", "routeId" to routeIdRaw)

    override fun loadNotificationSettings(): Map<String, Any?> =
      mapOf("source" to "$label-notification-settings")

    override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> {
      lastNotificationSettingsPayload = payload
      return mapOf("source" to "$label-notification-settings-save")
    }

    override fun loadStrongBackgroundSnapshot(): Map<String, Any?> =
      mapOf("source" to "$label-strong-background")

    override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> {
      lastStrongBackgroundActionId = actionId
      return mapOf("source" to "$label-strong-background-action", "actionId" to actionId)
    }

    override fun loadNetworkSearchConfig(): Map<String, Any?> =
      mapOf("source" to "$label-network-search")

    override fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?> =
      mapOf("source" to "$label-network-search-save", "slotCount" to slots.size)

    override fun loadMediaSpeechConfig(): Map<String, Any?> =
      mapOf("source" to "$label-media-speech")

    override fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?> =
      mapOf("source" to "$label-media-speech-save", "keys" to payload.keys.sorted())

    override fun loadSandboxSettings(): Map<String, Any?> =
      mapOf("source" to "$label-sandbox-settings")

    override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> {
      lastSandboxSettingsPayload = payload
      return mapOf("source" to "$label-sandbox-settings-save")
    }

    override fun loadLlmConfig(): Map<String, Any?> = mapOf("source" to "$label-llm")

    override fun saveLlmConfig(
      enabled: Boolean,
      providerId: String,
      selectedProviderOptionId: String,
      protocol: String,
      providerName: String,
      providerNotes: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
      systemPrompt: String,
    ): Map<String, Any?> = mapOf("source" to "$label-llm-save", "enabled" to enabled)

    override fun saveCustomLlmProvider(
      selectedProviderOptionId: String,
      protocol: String,
      providerName: String,
      providerNotes: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
      systemPrompt: String,
    ): Map<String, Any?> = mapOf("source" to "$label-custom-llm")

    override fun validateLlmConfig(
      providerId: String,
      protocol: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
    ): Map<String, Any?> = mapOf("source" to "$label-llm-validate", "providerId" to providerId)

    override fun loadPersonalizationConfig(): Map<String, Any?> =
      mapOf("source" to "$label-personalization")

    override fun savePersonalizationConfig(
      presetId: String,
      customLabel: String,
      customGuidance: String,
    ): Map<String, Any?> = mapOf("source" to "$label-personalization-save", "presetId" to presetId)

    override fun setAppLanguage(languageId: String): Map<String, Any?> =
      mapOf("source" to "$label-language", "languageId" to languageId)

    override fun runPersonalizationReset(scopeId: String): Map<String, Any?> =
      mapOf("source" to "$label-personalization-reset", "scopeId" to scopeId)

    override fun loadMcpSettings(): Map<String, Any?> =
      mapOf("source" to "$label-mcp")

    override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> {
      lastMcpMasterEnabled = enabled
      return mapOf("source" to "$label-mcp-master", "enabled" to enabled)
    }

    override fun setMcpServerEnabled(
      serverId: String,
      enabled: Boolean,
    ): Map<String, Any?> = mapOf(
      "source" to "$label-mcp-server",
      "serverId" to serverId,
      "enabled" to enabled,
    )

    override fun loadSafetySettings(): Map<String, Any?> =
      mapOf("source" to "$label-safety")

    override fun saveSafetySettings(
      automationModeId: String,
      rollbackJournalEnabled: Boolean,
      maxFilesPerBatch: Int,
      maxAgentTurns: Int,
      maxToolCalls: Int,
      undoWindowHours: Int,
      fileChangesPolicyId: String,
      fileDeletesPolicyId: String,
      shellCommandsPolicyId: String,
      externalAccessModeId: String,
      photoLibraryEnabled: Boolean,
      downloadsEnabled: Boolean,
      documentsEnabled: Boolean,
      recordingsEnabled: Boolean,
      workspaceAccessProfileId: String,
      readOnlyOutsideWorkspace: Boolean,
      liveContextModeId: String,
      memoryToolsEnabled: Boolean,
    ): Map<String, Any?> = mapOf(
      "source" to "$label-safety-save",
      "automationModeId" to automationModeId,
      "liveContextModeId" to liveContextModeId,
    )
  }

  private fun bridgeSnapshot(root: java.io.File): OpenCrayRuntimeServiceBridgeSnapshot {
    val runtimeManager = NoOpAgentSessionRuntimeManager()
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      private val stores = linkedMapOf<String, SessionSupplementStore>()

      override fun forChatSession(sessionId: String): SessionSupplementStore =
        stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
    }
    val approvalRegistry = AgentTaskApprovalRegistry()
    val memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
      memoryStore = InMemoryMemoryStore(),
    )
    val replayAccess = OpenCrayRuntimeReplayAccess(
      approvalRejectionRecorder = { _, _, _, _, _, _ -> },
      approvalApprovedRecorder = { _, _, _, _, _, _ -> },
      subAgentReplayRecorder = { _, _ -> },
      runCancellationRecorder = { _, _, _, _, _ -> },
      terminalReplayRepairer = { _, _ -> },
    )
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = lifecycleDescriptor,
      hostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = lifecycleDescriptor,
        sessionRuntimeManager = runtimeManager,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        supplementStoreFactory = supplementStoreFactory,
        approvalRegistry = approvalRegistry,
      ),
      transcriptMessagesProvider = { emptyList() },
      memoryIngestionCoordinator = memoryIngestionCoordinator,
      replayAccess = replayAccess,
    )
    val workspaceRoot = root.toPath()
    return OpenCrayRuntimeServiceBridgeSnapshot(
        dependencies = OpenCrayRuntimeContextDependencies(
          appContext = ContextWrapper(null),
          localizedContext = ContextWrapper(null),
          llmSettingsStore = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore()),
          sandboxSettingsRepository = testSandboxSettingsRepository(),
          personalizationStore = PersonalizationLocalStore(root.resolve("personalization")),
          chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session")),
          skillsFacade = EmptySkillsFacade,
        mcpSettingsFacade = EmptyMcpSettingsFacade,
        webSearchSettingsStore = WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore()),
        providerUserAgent = "OpenCrayRuntimeServiceHostTest",
        workspaceRootProvider = { workspaceRoot },
        workspaceRootsProvider = { setOf(workspaceRoot) },
        voiceMetadataCacheStore = null,
        soulProfileStore = WorkspaceSoulProfileStore(),
        liveContextModeStore = LiveContextModeStore(InMemoryLiveContextModeKeyValueStore()),
        safetySettingsFacade = EmptySafetySettingsFacade,
        mediaSpeechSettingsStore = MediaSpeechSettingsStore(InMemoryMediaSpeechSettingsKeyValueStore()),
        approvedReadRootsProvider = {
          ApprovedReadRootsSnapshot(
            roots = setOf(workspaceRoot),
            summary = "workspace=${workspaceRoot.toString().replace('\\', '/')}",
          )
        },
        workspaceSnapshotProvider = { emptyMap() },
      ),
      runtimeAccess = runtimeAccess,
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(),
      serviceWorkState = RuntimeServiceWorkStateTracker(
        workSummaryProvider = runtimeAccess.hostAccess::activeWorkSummary,
      ).apply {
        refresh()
      }.currentState(),
      serviceKeepAliveState = RuntimeServiceKeepAliveState(
        phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
        changedAtEpochMs = 1_000L,
      ),
    )
  }

  private fun projectionOnlyChatStrings(): ProjectionOnlyChatStrings = ProjectionOnlyChatStrings(
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
    summaryAwaitingDirection = "Waiting for your next instruction.",
    composerPlaceholder = "Message OpenCray",
    composerRejectedPlaceholder = "Message OpenCray differently",
  )

  private fun waitForCondition(
    timeoutMs: Long,
    condition: () -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (condition()) {
        return
      }
      Thread.sleep(10L)
    }
    assertTrue("Condition was not met within ${timeoutMs}ms.", condition())
  }
}
