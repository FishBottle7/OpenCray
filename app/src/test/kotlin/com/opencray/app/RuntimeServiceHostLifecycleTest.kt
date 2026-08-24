package com.opencray.app

import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceHostLifecycleTest : RuntimeServiceHostTestBase() {
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
    val owner = RetainedInProcessOpenCrayRuntimeOwnerCore(
      runtimeControllerLifecycle = null,
      runtimeOwnerLifecycleState = RuntimeOwnerLifecycleState(lifecycleDescriptor),
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
  fun runtimeServiceWorkStateTrackerTreatsLiveSubagentsAsActiveWork() {
    var now = 1_000L
    var summary = RuntimeOwnerWorkSummary()
    val tracker = RuntimeServiceWorkStateTracker(
      workSummaryProvider = { summary },
      clock = { now },
    )

    tracker.refresh()

    now = 2_000L
    summary = RuntimeOwnerWorkSummary(
      trackedSessionCount = 1,
      activeSessionIds = listOf("session-subagent"),
      liveSubAgentSessionIds = listOf("session-subagent"),
    )
    val active = tracker.refresh()

    assertEquals(true, active.hasActiveWork)
    assertEquals(1, active.activeSessionCount)
    assertEquals(1, active.liveSubAgentSessionCount)
    assertEquals(RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_SUBAGENT, active.keepAliveReason)
    assertEquals(true, active.keepAliveRequired)
  }

  @Test
  fun runtimeServiceKeepAliveControllerSchedulesIdleStopAfterGrace() {
    var now = 1_000L
    val scheduler = RecordingRuntimeServiceDelayScheduler()
    val requestedStopIds = mutableListOf<Int>()
    val controller = RuntimeServiceKeepAliveController(
      idleGraceMs = 500L,
      appVisibleProvider = { true },
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
      appVisibleProvider = { true },
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
  fun runtimeServiceKeepAliveControllerReschedulesIdleStopWhenAppGoesBackground() {
    var now = 1_000L
    val scheduler = RecordingRuntimeServiceDelayScheduler()
    val requestedStopIds = mutableListOf<Int>()
    val controller = RuntimeServiceKeepAliveController(
      idleGraceMs = 500L,
      backgroundIdleGraceMsProvider = { 3_000L },
      appVisibleProvider = { true },
      scheduler = scheduler,
      stopRequester = { startId ->
        requestedStopIds += startId
        true
      },
      clock = { now },
    )

    controller.onStartCommand(startId = 7)

    now = 1_200L
    val rescheduled = controller.onAppVisibilityChanged(false)

    assertEquals(listOf(500L, 2_800L), scheduler.scheduledDelayMs)
    assertEquals(RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE, rescheduled.phase)
    assertEquals(false, rescheduled.appVisible)
    assertEquals(3_000L, rescheduled.idleGraceMs)
    assertEquals(4_000L, rescheduled.stopDeadlineEpochMs)

    now = 4_000L
    scheduler.runNext()

    assertEquals(listOf(7), requestedStopIds)
    assertEquals(RuntimeServiceKeepAliveState.PHASE_STOP_REQUESTED, controller.currentState().phase)
  }

  @Test
  fun runtimeServiceKeepAliveControllerTransitionsToDestroyedOnDestroy() {
    var now = 3_000L
    val scheduler = RecordingRuntimeServiceDelayScheduler()
    val controller = RuntimeServiceKeepAliveController(
      idleGraceMs = 500L,
      appVisibleProvider = { true },
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
      appVisibleProvider = { true },
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
  fun runtimeForegroundControllerRetainsForegroundDuringBackgroundIdleGrace() {
    var now = 5_800L
    val adapter = RecordingRuntimeForegroundServiceAdapter()
    val controller = RuntimeForegroundController(
      serviceAdapter = adapter,
      retainForegroundDuringIdleGraceProvider = { true },
      appVisibleProvider = { true },
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
    now = 5_850L
    controller.onAppVisibilityChanged(false)
    controller.onKeepAliveStateChanged(
      RuntimeServiceKeepAliveState(
        phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
        idleGraceMs = 3_000L,
        appVisible = false,
        stopScheduled = true,
        stopDeadlineEpochMs = 8_800L,
        lastStartId = 7,
        changedAtEpochMs = now,
      ),
    )

    now = 5_900L
    val idleGrace = controller.onWorkStateChanged(
      RuntimeServiceWorkState(
        phase = RuntimeServiceWorkState.PHASE_IDLE,
        hasActiveWork = false,
        keepAliveRequired = false,
        changedAtEpochMs = now,
        idleSinceEpochMs = now,
      ),
    )

    assertEquals(RuntimeForegroundState.PHASE_FOREGROUND, idleGrace.phase)
    assertEquals(true, idleGrace.notificationVisible)
    assertEquals(RuntimeServiceWorkState.KEEP_ALIVE_REASON_IDLE_GRACE, idleGrace.keepAliveReason)
    assertEquals(2, adapter.startedModels.size)
    assertEquals(
      RuntimeServiceWorkState.KEEP_ALIVE_REASON_IDLE_GRACE,
      adapter.startedModels.last().keepAliveReason,
    )
    assertEquals(0, adapter.stopCount)
  }

  @Test
  fun runtimeForegroundControllerDoesNotRetainIdleGraceForegroundWhenRetentionDisabled() {
    var now = 5_950L
    val adapter = RecordingRuntimeForegroundServiceAdapter()
    val controller = RuntimeForegroundController(
      serviceAdapter = adapter,
      retainForegroundDuringIdleGraceProvider = { false },
      appVisibleProvider = { true },
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
    now = 5_980L
    controller.onAppVisibilityChanged(false)
    controller.onKeepAliveStateChanged(
      RuntimeServiceKeepAliveState(
        phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
        idleGraceMs = 3_000L,
        appVisible = false,
        stopScheduled = true,
        stopDeadlineEpochMs = 8_950L,
        lastStartId = 9,
        changedAtEpochMs = now,
      ),
    )

    now = 6_000L
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
    assertEquals(1, adapter.stopCount)
  }

  @Test
  fun runtimeForegroundControllerDoesNotRetainIdleGraceForegroundWhileAppVisible() {
    var now = 6_050L
    val adapter = RecordingRuntimeForegroundServiceAdapter()
    val controller = RuntimeForegroundController(
      serviceAdapter = adapter,
      retainForegroundDuringIdleGraceProvider = { true },
      appVisibleProvider = { true },
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
    now = 6_100L
    controller.onKeepAliveStateChanged(
      RuntimeServiceKeepAliveState(
        phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
        idleGraceMs = 3_000L,
        appVisible = true,
        stopScheduled = true,
        stopDeadlineEpochMs = 9_050L,
        lastStartId = 11,
        changedAtEpochMs = now,
      ),
    )

    now = 6_150L
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
    assertEquals(1, adapter.stopCount)
  }

  @Test
  fun runtimeForegroundControllerUpdatesForegroundWhenActiveCountsChange() {
    var now = 6_000L
    val adapter = RecordingRuntimeForegroundServiceAdapter()
    val controller = RuntimeForegroundController(
      serviceAdapter = adapter,
      appVisibleProvider = { true },
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
  fun runtimeForegroundControllerUpdatesForegroundWhenLiveManagedProcessCountsChange() {
    var now = 6_400L
    val adapter = RecordingRuntimeForegroundServiceAdapter()
    val controller = RuntimeForegroundController(
      serviceAdapter = adapter,
      appVisibleProvider = { false },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { now },
    )

    controller.onWorkStateChanged(
      RuntimeServiceWorkState(
        phase = RuntimeServiceWorkState.PHASE_ACTIVE_WORK,
        hasActiveWork = true,
        activeRunCount = 1,
        activeSessionCount = 3,
        pendingWorkSessionCount = 1,
        liveManagedProcessSessionCount = 1,
        keepAliveRequired = true,
        keepAliveReason = RuntimeServiceWorkState.KEEP_ALIVE_REASON_MANAGED_PROCESS,
        changedAtEpochMs = now,
        activeSinceEpochMs = now,
      ),
    )

    now = 6_700L
    val updated = controller.onWorkStateChanged(
      RuntimeServiceWorkState(
        phase = RuntimeServiceWorkState.PHASE_ACTIVE_WORK,
        hasActiveWork = true,
        activeRunCount = 1,
        activeSessionCount = 3,
        pendingWorkSessionCount = 1,
        liveManagedProcessSessionCount = 2,
        keepAliveRequired = true,
        keepAliveReason = RuntimeServiceWorkState.KEEP_ALIVE_REASON_MANAGED_PROCESS,
        changedAtEpochMs = now,
        activeSinceEpochMs = 6_400L,
      ),
    )

    assertEquals(RuntimeForegroundState.PHASE_FOREGROUND, updated.phase)
    assertEquals(2, updated.liveManagedProcessSessionCount)
    assertEquals(2, adapter.startedModels.size)
    assertEquals(2, adapter.startedModels.last().liveManagedProcessSessionCount)
  }

  @Test
  fun runtimeForegroundControllerUpdatesForegroundWhenLiveSubAgentCountsChange() {
    var now = 6_900L
    val adapter = RecordingRuntimeForegroundServiceAdapter()
    val controller = RuntimeForegroundController(
      serviceAdapter = adapter,
      appVisibleProvider = { false },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { now },
    )

    controller.onWorkStateChanged(
      RuntimeServiceWorkState(
        phase = RuntimeServiceWorkState.PHASE_ACTIVE_WORK,
        hasActiveWork = true,
        activeRunCount = 0,
        activeSessionCount = 3,
        liveSubAgentSessionCount = 1,
        keepAliveRequired = true,
        keepAliveReason = RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_SUBAGENT,
        changedAtEpochMs = now,
        activeSinceEpochMs = now,
      ),
    )

    now = 7_200L
    val updated = controller.onWorkStateChanged(
      RuntimeServiceWorkState(
        phase = RuntimeServiceWorkState.PHASE_ACTIVE_WORK,
        hasActiveWork = true,
        activeRunCount = 0,
        activeSessionCount = 3,
        liveSubAgentSessionCount = 2,
        keepAliveRequired = true,
        keepAliveReason = RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_SUBAGENT,
        changedAtEpochMs = now,
        activeSinceEpochMs = 6_900L,
      ),
    )

    assertEquals(RuntimeForegroundState.PHASE_FOREGROUND, updated.phase)
    assertEquals(2, updated.liveSubAgentSessionCount)
    assertEquals(2, adapter.startedModels.size)
    assertEquals(2, adapter.startedModels.last().liveSubAgentSessionCount)
    assertEquals(
      RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_SUBAGENT,
      adapter.startedModels.last().keepAliveReason,
    )
  }

  @Test
  fun bootstrapRuntimeServiceSessionsResumesAndRepairsActiveSession() {
    val root = temporaryFolder.newFolder("service-host-bootstrap")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    runtimeManager.putHandle(
      RecordingAgentSessionHandle(
        sessionId = activeSessionId,
        resumedSessionIds = runtimeManager.resumedSessionIds,
        hasPendingWork = true,
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

    val result = bootstrapRuntimeServiceSessions(chatSessionStore, runtimeAccess)

    assertEquals(listOf(activeSessionId), runtimeManager.resumedSessionIds)
    assertEquals(listOf(activeSessionId), result.scannedSessionIds)
    assertTrue(result.repairedSessionIds.isEmpty())
    assertTrue(repairCalls.isEmpty())
  }

  @Test
  fun bootstrapRuntimeServiceSessionsAlsoResumesNonActiveSessionsWithRetainedWork() {
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
        hasPendingWork = true,
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

    val result = bootstrapRuntimeServiceSessions(chatSessionStore, runtimeAccess)

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
  fun bootstrapRuntimeServiceSessionsAlsoResumesNonActiveSessionsWithLiveSubAgents() {
    val root = temporaryFolder.newFolder("service-host-bootstrap-subagent")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    chatSessionStore.appendUserMessage(activeSessionId, "Keep the active session")
    val subAgentSessionId = chatSessionStore.createSession().activeSession.sessionId
    chatSessionStore.appendUserMessage(subAgentSessionId, "Keep the background-subagent session")
    val idleSessionId = chatSessionStore.createSession().activeSession.sessionId
    chatSessionStore.appendUserMessage(idleSessionId, "Keep the idle session")
    chatSessionStore.selectSession(activeSessionId)

    val runtimeManager = RecordingAgentSessionRuntimeManager()
    runtimeManager.putHandle(
      RecordingAgentSessionHandle(
        sessionId = activeSessionId,
        resumedSessionIds = runtimeManager.resumedSessionIds,
        hasPendingWork = true,
      ),
    )
    runtimeManager.putHandle(
      RecordingAgentSessionHandle(
        sessionId = subAgentSessionId,
        resumedSessionIds = runtimeManager.resumedSessionIds,
        initialSubAgentHandles = listOf(backgroundSubAgentHandle(agentId = "child-live")),
      ),
    )
    runtimeManager.putHandle(
      RecordingAgentSessionHandle(
        sessionId = idleSessionId,
        resumedSessionIds = runtimeManager.resumedSessionIds,
      ),
    )
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
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(memoryStore = InMemoryMemoryStore()),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )

    val result = bootstrapRuntimeServiceSessions(chatSessionStore, runtimeAccess)

    assertEquals(
      setOf(activeSessionId, subAgentSessionId),
      result.resumedSessionIds.toSet(),
    )
    assertTrue(result.repairedSessionIds.isEmpty())
    assertEquals(
      setOf(activeSessionId, subAgentSessionId),
      runtimeManager.resumedSessionIds.toSet(),
    )
  }

  @Test
  fun bootstrapRuntimeServiceSessionsAlsoResumesNonActiveSessionsWithDurableSubAgentHandles() {
    val root = temporaryFolder.newFolder("service-host-bootstrap-durable-subagent")
    val runtimeRoot = root.resolve("runtime")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    chatSessionStore.appendUserMessage(activeSessionId, "Keep the active session")
    val durableSubAgentSessionId = chatSessionStore.createSession().activeSession.sessionId
    chatSessionStore.appendUserMessage(durableSubAgentSessionId, "Repair from durable subagent state")
    val idleSessionId = chatSessionStore.createSession().activeSession.sessionId
    chatSessionStore.appendUserMessage(idleSessionId, "Keep the idle session")
    chatSessionStore.selectSession(activeSessionId)
    val snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory(runtimeRoot)
    subAgentHandleStoreFactory.forChatSession(durableSubAgentSessionId).upsert(
      backgroundSubAgentHandle(agentId = "child-durable"),
    )

    val runtimeManager = RecordingAgentSessionRuntimeManager()
    runtimeManager.putHandle(
      RecordingAgentSessionHandle(
        sessionId = activeSessionId,
        resumedSessionIds = runtimeManager.resumedSessionIds,
        hasPendingWork = true,
      ),
    )
    runtimeManager.putHandle(
      RecordingAgentSessionHandle(
        sessionId = durableSubAgentSessionId,
        resumedSessionIds = runtimeManager.resumedSessionIds,
      ),
    )
    runtimeManager.putHandle(
      RecordingAgentSessionHandle(
        sessionId = idleSessionId,
        resumedSessionIds = runtimeManager.resumedSessionIds,
      ),
    )
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
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(memoryStore = InMemoryMemoryStore()),
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
    )

    assertEquals(
      setOf(activeSessionId, durableSubAgentSessionId),
      result.resumedSessionIds.toSet(),
    )
    assertTrue(result.repairedSessionIds.isEmpty())
    assertEquals(
      setOf(activeSessionId, durableSubAgentSessionId),
      runtimeManager.resumedSessionIds.toSet(),
    )
  }

  @Test
  fun bootstrapRuntimeServiceSessionsSubmitsRecoveryTaskForQueuedSubAgentWithoutActiveParentRun() {
    val root = temporaryFolder.newFolder("service-host-bootstrap-subagent-recovery")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    chatSessionStore.appendUserMessage(activeSessionId, "Keep the active session")
    val recoverySessionId = chatSessionStore.createSession().activeSession.sessionId
    chatSessionStore.appendUserMessage(recoverySessionId, "Recover the detached child")
    chatSessionStore.selectSession(activeSessionId)

    val runtimeManager = RecordingAgentSessionRuntimeManager()
    runtimeManager.putHandle(
      RecordingAgentSessionHandle(
        sessionId = activeSessionId,
        resumedSessionIds = runtimeManager.resumedSessionIds,
        hasPendingWork = true,
      ),
    )
    val recoveryHandle = queuedSubAgentHandle(agentId = "child-resume")
    val recoverySession = RecordingAgentSessionHandle(
      sessionId = recoverySessionId,
      resumedSessionIds = runtimeManager.resumedSessionIds,
      initialSubAgentHandles = listOf(recoveryHandle),
    )
    runtimeManager.putHandle(recoverySession)
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
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(memoryStore = InMemoryMemoryStore()),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )

    bootstrapRuntimeServiceSessions(chatSessionStore, runtimeAccess)

    assertEquals(1, recoverySession.detachedControlTasks.size)
    val recoveryTask = recoverySession.detachedControlTasks.single()
    assertEquals(AgentTaskType.SYSTEM, recoveryTask.type)
    assertEquals(
      SYNTHETIC_SUBAGENT_TASK_KIND_RECOVERY_WAIT,
      recoveryTask.metadata[METADATA_SYNTHETIC_SUBAGENT_TASK_KIND],
    )
    assertEquals(
      RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
      recoveryTask.metadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE],
    )
    assertEquals("child-resume", recoveryTask.metadata[METADATA_SUBAGENT_RECOVERY_AGENT_ID])
    assertEquals(
      recoveryHandle.parentRunId,
      recoveryTask.metadata[METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID],
    )
    assertTrue(recoverySession.submittedTasks.isEmpty())
    assertEquals(0, recoverySession.ensureProcessingCallCount)
  }

  @Test
  fun runtimeServiceGatewayBundleExposesNarrowGatewaysAndDispatchers() {
    val shellGateway = RecordingShellGateway("service")
    val chatGateway = RecordingChatRuntimeGateway("service")
    val skillsGateway = RecordingSkillsGateway("service")
    val settingsGateway = RecordingSettingsGateway("service")
    val bundle = OpenCrayRuntimeServiceGatewayBundle(
      shellGateway = shellGateway,
      chatRuntimeGateway = chatGateway,
      skillsGateway = skillsGateway,
      settingsGateway = settingsGateway,
    )

    assertEquals("service-shell", bundle.shellGateway.loadShellSnapshot()["source"])
    val chatDispatch = bundle.dispatchChatWriteCommand(
      OpenCrayChatWriteCommand.SubmitChatMessage("hello", emptyList()),
    )
    val skillsDispatch = bundle.dispatchSkillsWriteCommand(
      OpenCraySkillsWriteCommand.InstallSkillSource(
        sourceRef = "github:repo",
        selectedSkillName = "skill-a",
      ),
    )
    val settingsDispatch = bundle.dispatchSettingsWriteCommand(
      OpenCraySettingsWriteCommand.PerformStrongBackgroundAction(
        StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      ),
    )
    bundle.notifyChatSnapshotsChanged()

    assertEquals("hello", chatGateway.submittedText)
    assertEquals("github:repo", skillsGateway.lastInstalledSourceRef)
    assertEquals(
      StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      settingsGateway.lastStrongBackgroundActionId,
    )
    assertEquals(1, chatGateway.notifiedChatSnapshotCount)
    assertEquals(
      "service-submit",
      (chatDispatch as OpenCrayChatWriteDispatchResult.Payload).value?.get("source"),
    )
    assertEquals(
      "Installed github:repo via service.",
      (skillsDispatch as OpenCraySkillsWriteDispatchResult.Message).value,
    )
    assertEquals(
      "service-strong-background-action",
      (settingsDispatch as OpenCraySettingsWriteDispatchResult.Payload).value["source"],
    )
  }
}
