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
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.ProviderNativeWebSearchSupport
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentMailbox
import com.opencray.runtime.subagent.SubAgentMailboxMessage
import com.opencray.runtime.subagent.SubAgentMetadataKeys
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import java.util.ArrayDeque
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
  fun runtimeServiceHostApprovePendingApprovalResumesTaskWithoutHostRuntimeFacade() {
    val resumeState = OpenCrayPromptResumeState(turnIndex = 2, toolCallCount = 1)
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-approval"),
      resumeRequestResult = true,
      resultMetadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
        state = resumeState,
        json = Json { prettyPrint = false },
        checkpointBoundary = OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
      ) + mapOf(
        "toolName" to "Bash",
        "canonicalToolName" to "bash",
      ),
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertEquals(emptyList<String>(), fixture.handle.cancelledTaskIds)
    assertTrue(fixture.approvalRegistry.isApproved(fixture.sessionId, fixture.taskId))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(fixture.taskId)?.checkpointKind,
    )
    assertEquals(
      OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
      fixture.checkpointStore.get(fixture.taskId)?.promptCheckpointBoundary,
    )
    assertEquals(
      resumeState,
      fixture.checkpointStore.get(fixture.taskId)?.promptResumeState,
    )
    val approvalEvents = fixture.journalStore.listRuntimeEvents()
      .filterIsInstance<OpenCrayApprovalEvent>()
    assertEquals(OpenCrayApprovalPhase.APPROVED, approvalEvents.lastOrNull()?.phase)
    assertEquals(
      "Approval granted. The agent is resuming.",
      fixture.chatStore.loadSession(fixture.sessionId)?.messages?.lastOrNull()?.text,
    )
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalForSessionResumesTaskWithoutHostRuntimeFacade() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-approval-for-session"),
      resumeRequestResult = true,
      resultMetadata = mapOf(
        "toolName" to "WebSearch",
        com.opencray.runtime.OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME to
          ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
        ProviderNativeWebSearchSupport.METADATA_APPROVAL_KIND to
          ProviderNativeWebSearchSupport.APPROVAL_KIND,
        ProviderNativeWebSearchSupport.METADATA_SUPPORTS_SESSION_APPROVAL to "true",
      ),
      checkpointToolName = ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
    )

    fixture.serviceHost.approvePendingApprovalForSession(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertTrue(fixture.chatStore.isNativeWebSearchSessionApproved(fixture.sessionId))
    assertTrue(fixture.approvalRegistry.isApproved(fixture.sessionId, fixture.taskId))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(fixture.taskId)?.checkpointKind,
    )
    assertEquals(
      ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
      fixture.checkpointStore.get(fixture.taskId)?.toolName,
    )
    val approvalEvents = fixture.journalStore.listRuntimeEvents()
      .filterIsInstance<OpenCrayApprovalEvent>()
    assertEquals(OpenCrayApprovalPhase.APPROVED, approvalEvents.lastOrNull()?.phase)
    assertEquals(
      "Approval granted for this session. The agent is resuming.",
      fixture.chatStore.loadSession(fixture.sessionId)?.messages?.lastOrNull()?.text,
    )
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalRecordsSubAgentReplayWithoutHostRuntimeFacade() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-approval-subagent"),
      resumeRequestResult = true,
      resultMetadata = mapOf(
        "toolName" to "Bash",
        "canonicalToolName" to "bash",
        "childRunId" to "child-run-1",
        "childTaskId" to "child-task-1",
        "subagentType" to "search",
        "delegationDescription" to "Delegate",
        "subagentContextMode" to "delegated",
        "subagentDepth" to "2",
      ),
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(1, fixture.subAgentReplayEvents.size)
    assertEquals(OpenCraySubAgentPhase.RESUMED, fixture.subAgentReplayEvents.single().phase)
    assertEquals("child-run-1", fixture.subAgentReplayEvents.single().childRunId)
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalForExplicitHandleSubmitsDetachedRecoveryTask() {
    val childResume = SubAgentApprovalResume(
      approvedToolName = "Edit",
      promptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
      agentId = "child-explicit",
      childRunId = "child-run-child-explicit",
      childTaskId = "child-task-child-explicit",
    )
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-explicit-subagent"),
      resultMetadata = SubAgentApprovalResumeMetadata.encodeToMetadata(
        resume = childResume,
        json = Json { prettyPrint = false },
      ) + mapOf(
        "toolName" to "Edit",
        "canonicalToolName" to "edit",
        "childRunId" to childResume.childRunId.orEmpty(),
        "childTaskId" to childResume.childTaskId.orEmpty(),
        "subagentType" to "worker",
        "delegationDescription" to "Edit notes",
        "subagentContextMode" to "delegated",
        "subagentDepth" to "2",
        SubAgentMetadataKeys.CONTROL_TOOL to "wait_agent",
      ),
      subAgentHandles = listOf(waitingApprovalSubAgentHandle(agentId = "child-explicit")),
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(emptyList<String>(), fixture.handle.resumedTaskIds)
    assertFalse(fixture.approvalRegistry.isApproved(fixture.sessionId, fixture.taskId))
    assertEquals(1, fixture.handle.detachedControlTasks.size)
    val detachedTask = fixture.handle.detachedControlTasks.single()
    assertEquals(AgentTaskType.SYSTEM, detachedTask.type)
    assertEquals(
      DETACHED_CONTROL_KIND_SUBAGENT_RECOVERY_WAIT,
      detachedTask.metadata[METADATA_DETACHED_CONTROL_KIND],
    )
    assertEquals(
      detachedSubAgentRecoveryTaskId(
        sessionId = fixture.sessionId,
        agentId = "child-explicit",
        parentRunId = "parent-run-child-explicit",
      ),
      detachedTask.id,
    )
    assertEquals("child-explicit", detachedTask.metadata[METADATA_SUBAGENT_RECOVERY_AGENT_ID])
    assertEquals(
      "parent-run-child-explicit",
      detachedTask.metadata[METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID],
    )
    assertTrue(fixture.approvalRegistry.isApproved(fixture.sessionId, detachedTask.id))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(detachedTask.id)?.checkpointKind,
    )
    assertTrue(
      fixture.chatStore.loadSession(fixture.sessionId)?.messages?.lastOrNull()?.text in setOf(
        "审批已通过，子任务正在后台继续执行。",
        "Approval granted. The delegated child is resuming in the background.",
      ),
    )
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalForSessionWithExplicitHandleSubmitsDetachedRecoveryTask() {
    val childResume = SubAgentApprovalResume(
      approvedToolName = ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
      promptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
      agentId = "child-explicit-session",
      childRunId = "child-run-child-explicit-session",
      childTaskId = "child-task-child-explicit-session",
    )
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-session-explicit-subagent"),
      resultMetadata = SubAgentApprovalResumeMetadata.encodeToMetadata(
        resume = childResume,
        json = Json { prettyPrint = false },
      ) + mapOf(
        "toolName" to "WebSearch",
        com.opencray.runtime.OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME to
          ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
        ProviderNativeWebSearchSupport.METADATA_APPROVAL_KIND to
          ProviderNativeWebSearchSupport.APPROVAL_KIND,
        ProviderNativeWebSearchSupport.METADATA_SUPPORTS_SESSION_APPROVAL to "true",
        "childRunId" to childResume.childRunId.orEmpty(),
        "childTaskId" to childResume.childTaskId.orEmpty(),
        "subagentType" to "researcher",
        "delegationDescription" to "Search docs",
        "subagentContextMode" to "delegated",
        "subagentDepth" to "2",
        SubAgentMetadataKeys.CONTROL_TOOL to "wait_agent",
      ),
      checkpointToolName = ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
      subAgentHandles = listOf(waitingApprovalSubAgentHandle(agentId = "child-explicit-session")),
    )

    fixture.serviceHost.approvePendingApprovalForSession(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(emptyList<String>(), fixture.handle.resumedTaskIds)
    assertTrue(fixture.chatStore.isNativeWebSearchSessionApproved(fixture.sessionId))
    assertFalse(fixture.approvalRegistry.isApproved(fixture.sessionId, fixture.taskId))
    assertEquals(1, fixture.handle.detachedControlTasks.size)
    val detachedTask = fixture.handle.detachedControlTasks.single()
    assertEquals(AgentTaskType.SYSTEM, detachedTask.type)
    assertEquals(
      DETACHED_CONTROL_KIND_SUBAGENT_RECOVERY_WAIT,
      detachedTask.metadata[METADATA_DETACHED_CONTROL_KIND],
    )
    assertEquals(
      detachedSubAgentRecoveryTaskId(
        sessionId = fixture.sessionId,
        agentId = "child-explicit-session",
        parentRunId = "parent-run-child-explicit-session",
      ),
      detachedTask.id,
    )
    assertEquals(
      "child-explicit-session",
      detachedTask.metadata[METADATA_SUBAGENT_RECOVERY_AGENT_ID],
    )
    assertTrue(fixture.approvalRegistry.isApproved(fixture.sessionId, detachedTask.id))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(detachedTask.id)?.checkpointKind,
    )
    assertTrue(
      fixture.chatStore.loadSession(fixture.sessionId)?.messages?.lastOrNull()?.text in setOf(
        "审批已通过，子任务正在后台继续执行。",
        "Approval granted. The delegated child is resuming in the background.",
      ),
    )
  }

  @Test
  fun runtimeServiceHostRejectPendingApprovalCancelsTaskWithoutHostRuntimeFacade() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-reject-approval"),
      cancelRequestResult = true,
    )

    fixture.serviceHost.rejectPendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(emptyList<String>(), fixture.handle.resumedTaskIds)
    assertEquals(listOf(fixture.taskId), fixture.handle.cancelledTaskIds)
    assertFalse(fixture.approvalRegistry.isRejected(fixture.sessionId, fixture.taskId))
    assertNull(fixture.checkpointStore.get(fixture.taskId))
    val approvalEvents = fixture.journalStore.listRuntimeEvents()
      .filterIsInstance<OpenCrayApprovalEvent>()
    assertEquals(OpenCrayApprovalPhase.REJECTED, approvalEvents.lastOrNull()?.phase)
    assertEquals(
      "Approval rejected. The requested action was not run.",
      fixture.chatStore.loadSession(fixture.sessionId)?.messages?.lastOrNull()?.text,
    )
  }

  @Test
  fun runtimeServiceHostRejectPendingApprovalRecordsSubAgentCancellationWithoutHostRuntimeFacade() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-reject-approval-subagent"),
      cancelRequestResult = true,
      resultMetadata = mapOf(
        "toolName" to "Bash",
        "canonicalToolName" to "bash",
        "childRunId" to "child-run-2",
        "childTaskId" to "child-task-2",
        "subagentType" to "search",
        "delegationDescription" to "Delegate",
        "subagentContextMode" to "delegated",
        "subagentDepth" to "2",
      ),
    )

    fixture.serviceHost.rejectPendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(1, fixture.subAgentReplayEvents.size)
    assertEquals(OpenCraySubAgentPhase.CANCELLED, fixture.subAgentReplayEvents.single().phase)
    assertEquals("child-run-2", fixture.subAgentReplayEvents.single().childRunId)
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalAfterInterruptDefersResumeInCheckpoint() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-deferred-approval"),
      resumeRequestResult = true,
      appendInterruptEvent = true,
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(emptyList<String>(), fixture.handle.resumedTaskIds)
    assertFalse(fixture.approvalRegistry.isApproved(fixture.sessionId, fixture.taskId))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(fixture.taskId)?.checkpointKind,
    )
    val approvalEvents = fixture.journalStore.listRuntimeEvents()
      .filterIsInstance<OpenCrayApprovalEvent>()
    assertEquals(OpenCrayApprovalPhase.APPROVED, approvalEvents.lastOrNull()?.phase)
    assertTrue(
      fixture.chatStore.loadSession(fixture.sessionId)?.messages?.lastOrNull()?.text in setOf(
        "审批已通过。此决定已记录；手动继续运行后才会生效。",
        "Approval granted. The decision is recorded and will apply when you manually resume the run.",
      ),
    )
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalResumesDetachedApprovalRunWithoutQueueTaskSnapshot() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-detached-approve-approval"),
      resumeRequestResult = true,
      includeQueueApprovalTask = false,
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertEquals(emptyList<String>(), fixture.handle.cancelledTaskIds)
    assertTrue(fixture.approvalRegistry.isApproved(fixture.sessionId, fixture.taskId))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(fixture.taskId)?.checkpointKind,
    )
  }

  @Test
  fun runtimeServiceHostRejectPendingApprovalCancelsDetachedApprovalRunWithoutQueueTaskSnapshot() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-detached-reject-approval"),
      cancelRequestResult = true,
      includeQueueApprovalTask = false,
    )

    fixture.serviceHost.rejectPendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(emptyList<String>(), fixture.handle.resumedTaskIds)
    assertEquals(listOf(fixture.taskId), fixture.handle.cancelledTaskIds)
    assertFalse(fixture.approvalRegistry.isRejected(fixture.sessionId, fixture.taskId))
    assertNull(fixture.checkpointStore.get(fixture.taskId))
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
  fun runtimeForegroundControllerBootstrapForegroundStopsWhenServiceReturnsIdle() {
    var now = 6_500L
    val adapter = RecordingRuntimeForegroundServiceAdapter()
    val controller = RuntimeForegroundController(
      serviceAdapter = adapter,
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { now },
    )

    val bootstrap = controller.startBootstrapForeground()

    assertEquals(RuntimeForegroundState.PHASE_FOREGROUND, bootstrap.phase)
    assertEquals(true, bootstrap.notificationVisible)
    assertEquals(
      RuntimeServiceWorkState.KEEP_ALIVE_REASON_SERVICE_STARTUP,
      bootstrap.keepAliveReason,
    )
    assertEquals(1, adapter.startedModels.size)
    assertEquals(
      RuntimeServiceWorkState.KEEP_ALIVE_REASON_SERVICE_STARTUP,
      adapter.startedModels.single().keepAliveReason,
    )

    now = 6_800L
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
  fun bootstrapRuntimeServiceSessionsResumesAndRepairsActiveSession() {
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
      ),
    )
    runtimeManager.putHandle(
      RecordingAgentSessionHandle(
        sessionId = subAgentSessionId,
        resumedSessionIds = runtimeManager.resumedSessionIds,
        subAgentHandles = listOf(backgroundSubAgentHandle(agentId = "child-live")),
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
      ),
    )
    val recoveryHandle = queuedSubAgentHandle(agentId = "child-resume")
    val recoverySession = RecordingAgentSessionHandle(
      sessionId = recoverySessionId,
      resumedSessionIds = runtimeManager.resumedSessionIds,
      subAgentHandles = listOf(recoveryHandle),
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
      DETACHED_CONTROL_KIND_SUBAGENT_RECOVERY_WAIT,
      recoveryTask.metadata[METADATA_DETACHED_CONTROL_KIND],
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
  fun inProcessBridgeLoadsSnapshotFromSnapshotProvider() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("in-process-bridge"))
    val bridge = InProcessOpenCrayRuntimeServiceBridge { expected }

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
  fun existingBridgeLoadsSnapshotFromSnapshotProvider() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("existing-bridge"))
    val bridge = ExistingOpenCrayRuntimeServiceBridge(
      snapshotProvider = { expected },
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
      snapshotProvider = { null },
      missingHostMessage = "runtime host missing",
    )

    val failure = runCatching { bridge.loadSnapshot() }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertEquals("runtime host missing", failure?.message)
  }

  @Test
  fun missingBridgeFailsExplicitly() {
    val bridge = MissingOpenCrayRuntimeServiceBridge(
      missingSnapshotMessage = "binder snapshot missing",
    )

    val failure = runCatching { bridge.loadSnapshot() }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertEquals("binder snapshot missing", failure?.message)
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
      bridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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

  @Test
  fun runtimeServiceHostApprovePendingApprovalForExplicitHandleIgnoresSameChildFromDifferentParentRun() {
    val childResume = SubAgentApprovalResume(
      approvedToolName = "Edit",
      promptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
      agentId = "child-explicit",
      childRunId = "child-run-child-explicit",
      childTaskId = "child-task-child-explicit",
    )
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-explicit-subagent-parent-match"),
      resultMetadata = SubAgentApprovalResumeMetadata.encodeToMetadata(
        resume = childResume,
        json = Json { prettyPrint = false },
      ) + mapOf(
        "toolName" to "Edit",
        "canonicalToolName" to "edit",
        "parentRunId" to "parent-run-child-explicit",
        "childRunId" to childResume.childRunId.orEmpty(),
        "childTaskId" to childResume.childTaskId.orEmpty(),
        "subagentType" to "worker",
        "delegationDescription" to "Edit notes",
        "subagentContextMode" to "delegated",
        "subagentDepth" to "2",
        SubAgentMetadataKeys.CONTROL_TOOL to "wait_agent",
      ),
      subAgentHandles = listOf(
        waitingApprovalSubAgentHandle(agentId = "child-explicit").copy(
          parentRunId = "parent-run-wrong",
        ),
        waitingApprovalSubAgentHandle(agentId = "child-explicit").copy(
          parentRunId = "parent-run-child-explicit",
        ),
      ),
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(1, fixture.handle.detachedControlTasks.size)
    assertEquals(
      "parent-run-child-explicit",
      fixture.handle.detachedControlTasks.single()
        .metadata[METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID],
    )
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalForExplicitHandleWithoutParentRunIdUsesUniqueHandleParentRun() {
    val childResume = SubAgentApprovalResume(
      approvedToolName = "Edit",
      promptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
      agentId = "child-explicit",
      childRunId = "child-run-child-explicit",
      childTaskId = "child-task-child-explicit",
    )
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-explicit-subagent-no-parent-run"),
      resultMetadata = SubAgentApprovalResumeMetadata.encodeToMetadata(
        resume = childResume,
        json = Json { prettyPrint = false },
      ) + mapOf(
        "toolName" to "Edit",
        "canonicalToolName" to "edit",
        "childRunId" to childResume.childRunId.orEmpty(),
        "childTaskId" to childResume.childTaskId.orEmpty(),
        "subagentType" to "worker",
        "delegationDescription" to "Edit notes",
        "subagentContextMode" to "delegated",
        "subagentDepth" to "2",
        SubAgentMetadataKeys.CONTROL_TOOL to "wait_agent",
      ),
      subAgentHandles = listOf(waitingApprovalSubAgentHandle(agentId = "child-explicit")),
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(emptyList<String>(), fixture.handle.resumedTaskIds)
    assertEquals(1, fixture.handle.detachedControlTasks.size)
    assertEquals(
      "parent-run-child-explicit",
      fixture.handle.detachedControlTasks.single()
        .metadata[METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID],
    )
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayEmitsRuntimeDeltaWhenManagedProcessOutputChangesWithoutNewEvents() {
    val readGateway = RecordingChatRuntimeGateway("projection")
    readGateway.chatRuntimePayload = mapOf(
      "sessionId" to "session-1",
      "updatedAtEpochMs" to 1100L,
      "activeRuns" to listOf(
        mapOf(
          "sessionId" to "session-1",
          "runId" to "run-1",
          "taskId" to "task-1",
          "acceptedAtEpochMs" to 1000L,
          "updatedAtEpochMs" to 1100L,
          "attempt" to 1,
          "pendingMessageId" to "pending-1",
          "isTerminal" to false,
          "managedProcessIds" to listOf("proc-1"),
          "managedProcesses" to listOf(
            mapOf(
              "processId" to "proc-1",
              "status" to "running",
              "command" to "npm",
              "args" to listOf("run", "dev"),
              "workingDirectory" to ".",
              "processStarted" to true,
              "startedAtEpochMs" to 1050L,
              "updatedAtEpochMs" to 1100L,
              "stdoutPreview" to "starting dev server",
            ),
          ),
          "runningManagedProcessCount" to 1,
          "hasLiveManagedProcesses" to true,
        ),
      ),
      "retainedRuns" to emptyList<Map<String, Any?>>(),
      "subAgents" to emptyList<Map<String, Any?>>(),
      "events" to listOf(
        mapOf(
          "kind" to "lifecycle",
          "runId" to "run-1",
          "taskId" to "task-1",
          "emittedAtEpochMs" to 1000L,
          "phase" to "start",
        ),
      ),
    )
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = readGateway,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedDeltas = mutableListOf<Map<String, Any?>>()
    val dispose = gateway.observeRuntimeEventDeltas { payload ->
      observedDeltas += payload
    }

    readGateway.chatRuntimePayload = mapOf(
      "sessionId" to "session-1",
      "updatedAtEpochMs" to 1400L,
      "activeRuns" to listOf(
        mapOf(
          "sessionId" to "session-1",
          "runId" to "run-1",
          "taskId" to "task-1",
          "acceptedAtEpochMs" to 1000L,
          "updatedAtEpochMs" to 1400L,
          "attempt" to 1,
          "pendingMessageId" to "pending-1",
          "isTerminal" to false,
          "managedProcessIds" to listOf("proc-1"),
          "managedProcesses" to listOf(
            mapOf(
              "processId" to "proc-1",
              "status" to "running",
              "command" to "npm",
              "args" to listOf("run", "dev"),
              "workingDirectory" to ".",
              "processStarted" to true,
              "startedAtEpochMs" to 1050L,
              "updatedAtEpochMs" to 1400L,
              "stdoutPreview" to "ready on http://localhost:3000",
            ),
          ),
          "runningManagedProcessCount" to 1,
          "hasLiveManagedProcesses" to true,
        ),
      ),
      "retainedRuns" to emptyList<Map<String, Any?>>(),
      "subAgents" to emptyList<Map<String, Any?>>(),
      "events" to listOf(
        mapOf(
          "kind" to "lifecycle",
          "runId" to "run-1",
          "taskId" to "task-1",
          "emittedAtEpochMs" to 1000L,
          "phase" to "start",
        ),
      ),
    )

    gateway.notifyChatSnapshotsChanged()

    assertEquals(1, observedDeltas.size)
    assertEquals("session-1", observedDeltas.single()["sessionId"])
    assertEquals(1L, observedDeltas.single()["sequence"])
    assertEquals(1, (observedDeltas.single()["totalLength"] as Int))
    assertTrue(((observedDeltas.single()["events"] as List<*>)).isEmpty())
    val activeRuns = observedDeltas.single()["activeRuns"] as List<Map<String, Any?>>
    val managedProcesses =
      activeRuns.single()["managedProcesses"] as List<Map<String, Any?>>
    assertEquals(
      "ready on http://localhost:3000",
      managedProcesses.single()["stdoutPreview"],
    )
    dispose()
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesNotificationAndStrongBackgroundWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val strongBackgroundAccess = RecordingStrongBackgroundSettingsAccess()
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .createForTest(),
      strongBackgroundSettingsAccess = strongBackgroundAccess,
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = RecordingLlmConfigFacade(),
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      runtimeServiceConnectionStateProvider = { RuntimeServiceConnectionState.binderConnected() },
    )

    val initial = gateway.loadNotificationSettings()
    val strongBackground = gateway.loadStrongBackgroundSnapshot()
    val saved = gateway.saveNotificationSettings(
      mapOf(
        "masterEnabled" to false,
        "taskFinishedEnabled" to true,
        "serviceRecoveredEnabled" to true,
      ),
    )
    gateway.performStrongBackgroundAction(StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS)

    assertEquals(true, initial["masterEnabled"])
    assertEquals("service-strong-background", strongBackground["source"])
    assertEquals(
      "bound",
      ((strongBackground["runtimeServiceConnectionState"] as Map<String, Any?>)["phase"]),
    )
    assertEquals(false, saved["masterEnabled"])
    assertEquals(true, saved["taskFinishedEnabled"])
    assertEquals(true, saved["serviceRecoveredEnabled"])
    assertNull(delegate.lastNotificationSettingsPayload)
    assertNull(delegate.lastStrongBackgroundActionId)
    assertEquals(
      StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      strongBackgroundAccess.lastActionId,
    )
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesNetworkSearchAndMediaSpeechWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val networkFacade = RecordingNetworkSearchConfigFacade()
    val mediaFacade = RecordingMediaSpeechSettingsFacade()
    var settingsOverviewNotificationCount = 0
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .createForTest(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = networkFacade,
      mediaSpeechSettingsFacade = mediaFacade,
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = RecordingLlmConfigFacade(),
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      settingsOverviewNotifier = { settingsOverviewNotificationCount += 1 },
    )

    val network = gateway.loadNetworkSearchConfig()
    val savedNetwork = gateway.saveNetworkSearchConfig(
      listOf(
        mapOf(
          "id" to "secondary",
          "providerId" to "perplexity",
          "label" to "Perplexity",
          "baseUrl" to "https://search.example.com",
          "model" to "sonar-pro",
          "apiKey" to "search-key",
          "enabled" to true,
        ),
      ),
    )
    val media = gateway.loadMediaSpeechConfig()
    val savedMedia = gateway.saveMediaSpeechConfig(
      mapOf(
        "imageGeneration" to mapOf(
          "provider" to "openrouter",
          "baseUrl" to "https://image.example.com",
          "endpoint" to "/images",
          "model" to "gpt-image-1",
          "authProtocol" to ProviderAuthProtocols.BEARER,
          "apiKey" to "image-key",
        ),
        "videoGeneration" to mapOf(
          "provider" to "runway",
          "baseUrl" to "https://video.example.com",
          "endpoint" to "/videos",
          "model" to "gen4",
          "authProtocol" to ProviderAuthProtocols.ANTHROPIC,
          "apiKey" to "video-key",
        ),
        "voiceGeneration" to mapOf(
          "provider" to "elevenlabs",
          "baseUrl" to "https://voice.example.com",
          "endpoint" to "/speech",
          "model" to "tts-omni",
          "voicePreset" to "alloy",
          "authProtocol" to ProviderAuthProtocols.NONE,
          "apiKey" to "",
        ),
        "sttRouteId" to "external",
        "externalStt" to mapOf(
          "provider" to "deepgram",
          "baseUrl" to "https://stt.example.com",
          "endpoint" to "/listen",
          "model" to "nova-3",
          "authProtocol" to ProviderAuthProtocols.BEARER,
          "apiKey" to "stt-key",
        ),
        "onDeviceModel" to mapOf(
          "modelPackage" to "tiny.en",
          "downloadStatus" to "ready",
        ),
      ),
    )

    assertEquals("Network & Search", network["title"])
    assertEquals("perplexity", networkFacade.lastSavedRequest?.slots?.single()?.providerId)
    assertEquals(
      "perplexity",
      (savedNetwork["slots"] as List<Map<String, Any?>>).single()["providerId"],
    )
    assertEquals("Media & Speech", media["title"])
    assertEquals("external", mediaFacade.lastSavedRequest?.sttRouteId)
    assertEquals(
      "openrouter",
      (savedMedia["imageGeneration"] as Map<String, Any?>)["provider"],
    )
    assertEquals(
      "runway",
      (savedMedia["videoGeneration"] as Map<String, Any?>)["provider"],
    )
    assertEquals("tts-omni", mediaFacade.lastSavedRequest?.voiceGeneration?.model)
    assertEquals(
      ProviderAuthProtocols.ANTHROPIC,
      mediaFacade.lastSavedRequest?.videoGeneration?.authProtocol,
    )
    assertEquals(2, settingsOverviewNotificationCount)
    assertNull(delegate.lastNetworkSearchSlots)
    assertNull(delegate.lastMediaSpeechPayload)
    assertEquals("delegate-network-search", delegate.loadNetworkSearchConfig()["source"])
    assertEquals("delegate-media-speech", delegate.loadMediaSpeechConfig()["source"])
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesOverviewDetailAndObserveWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val settingsFacade = RecordingServiceOwnedSettingsFacade()
    val observedTitles = mutableListOf<String>()
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = settingsFacade,
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .createForTest(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = RecordingLlmConfigFacade(),
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      settingsOverviewNotifier = { },
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    val disposer = gateway.observeSettingsOverview { snapshot ->
      observedTitles += snapshot["title"] as String
    }
    try {
      val overview = gateway.loadSettingsOverview()
      val detail = gateway.loadSettingsDetail("personalization")

      assertEquals("Settings", overview["title"])
      assertEquals("personalization", detail["routeId"])
      assertEquals(listOf("Settings"), observedTitles)
      assertEquals("delegate-settings", delegate.loadSettingsOverview()["source"])
      assertEquals("delegate-settings-detail", delegate.loadSettingsDetail("personalization")["source"])
      assertEquals(
        com.opencray.app.facade.settings.SettingsRouteId.PERSONALIZATION,
        settingsFacade.lastLoadedDetailRouteId,
      )
    } finally {
      disposer()
    }
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesSandboxWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val sandboxAccess = RecordingSandboxSettingsGatewayAccess()
    var settingsOverviewNotificationCount = 0
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .createForTest(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      sandboxSettingsAccess = sandboxAccess,
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = RecordingLlmConfigFacade(),
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      settingsOverviewNotifier = { settingsOverviewNotificationCount += 1 },
    )

    val sandbox = gateway.loadSandboxSettings()
    val savedSandbox = gateway.saveSandboxSettings(
      mapOf(
        "enabled" to true,
        "providerId" to "e2b",
        "defaultBackend" to "envd",
        "sessionMode" to "ephemeral",
        "autoResume" to true,
        "idleTimeoutMinutes" to 45,
        "startupTimeoutMs" to 120000L,
        "requestTimeoutMs" to 240000L,
        "timeoutAction" to "restart",
        "templateId" to "python",
        "e2bApiKey" to "sandbox-secret",
      ),
    )

    assertEquals(false, sandbox["enabled"])
    assertEquals("e2b", sandboxAccess.lastSavedState?.providerId)
    assertEquals("sandbox-secret", sandboxAccess.lastSavedApiKey)
    assertEquals("python", savedSandbox["templateId"])
    assertEquals(true, savedSandbox["apiKeyConfigured"])
    assertEquals(1, settingsOverviewNotificationCount)
    assertNull(delegate.lastSandboxSettingsPayload)
    assertEquals("delegate-sandbox-settings", delegate.loadSandboxSettings()["source"])
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesPersonalizationSaveAndResetWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val personalizationFacade = RecordingPersonalizationFacade()
    val appLanguageAccess = RecordingAppLanguageSettingsGatewayAccess()
    var settingsOverviewNotificationCount = 0
    var shellSnapshotNotificationCount = 0
    var chatSnapshotNotificationCount = 0
    var localizedRefreshCount = 0
    var skillsSnapshotNotificationCount = 0
    var skillsProjectionNotificationCount = 0
    var localSettingsOverviewEmissionCount = 0
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .createForTest(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      appLanguageSettingsAccess = appLanguageAccess,
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = personalizationFacade,
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = RecordingLlmConfigFacade(),
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      shellSnapshotNotifier = { shellSnapshotNotificationCount += 1 },
      chatSnapshotNotifier = { chatSnapshotNotificationCount += 1 },
      settingsOverviewNotifier = { settingsOverviewNotificationCount += 1 },
      skillsSnapshotNotifier = { skillsSnapshotNotificationCount += 1 },
      skillsProjectionNotifier = { skillsProjectionNotificationCount += 1 },
      localizedResourcesRefresh = { localizedRefreshCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val disposer = gateway.observeSettingsOverview {
      localSettingsOverviewEmissionCount += 1
    }
    try {
      val personalization = gateway.loadPersonalizationConfig()
      val savedPersonalization = gateway.savePersonalizationConfig(
        presetId = "warm",
        customLabel = "Custom Coach",
        customGuidance = "Push harder on follow-through.",
      )
      val resetPersonalization = gateway.runPersonalizationReset("memory")
      val delegatedLanguage = gateway.setAppLanguage("zh-CN")

      assertEquals("Personalization", personalization["title"])
      assertEquals("warm", personalizationFacade.lastSavedRequest?.presetId)
      assertEquals("Custom Coach", savedPersonalization["customLabel"])
      assertEquals("memory", personalizationFacade.lastResetScope?.wireValue)
      assertEquals("Memory reset staged.", resetPersonalization["lastResetMessage"])
      assertEquals(3, settingsOverviewNotificationCount)
      assertEquals(1, shellSnapshotNotificationCount)
      assertEquals(1, chatSnapshotNotificationCount)
      assertEquals(1, localizedRefreshCount)
      assertEquals(1, skillsSnapshotNotificationCount)
      assertEquals(1, skillsProjectionNotificationCount)
      assertEquals(4, localSettingsOverviewEmissionCount)
      assertEquals("zh-CN", appLanguageAccess.lastLanguageId)
      assertNull(delegate.lastPersonalizationPresetId)
      assertNull(delegate.lastPersonalizationResetScopeId)
      assertNull(delegate.lastAppLanguageId)
      assertEquals("service-language", delegatedLanguage["source"])
    } finally {
      disposer()
    }
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesSafetyWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val safetyFacade = RecordingSafetySettingsFacade()
    var chatSnapshotNotificationCount = 0
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .createForTest(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = safetyFacade,
      llmConfigFacade = RecordingLlmConfigFacade(),
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      chatSnapshotNotifier = { chatSnapshotNotificationCount += 1 },
    )

    val safety = gateway.loadSafetySettings()
    val savedSafety = gateway.saveSafetySettings(
      automationModeId = "confirm",
      rollbackJournalEnabled = true,
      maxFilesPerBatch = 8,
      maxAgentTurns = 16,
      maxToolCalls = 24,
      undoWindowHours = 12,
      fileChangesPolicyId = "ask",
      fileDeletesPolicyId = "deny",
      shellCommandsPolicyId = "ask",
      externalAccessModeId = "allow",
      photoLibraryEnabled = true,
      downloadsEnabled = true,
      documentsEnabled = false,
      recordingsEnabled = false,
      workspaceAccessProfileId = "workspace_write",
      readOnlyOutsideWorkspace = true,
      liveContextModeId = "lightweight",
      memoryToolsEnabled = false,
    )

    assertEquals("auto", safety["automationModeId"])
    assertEquals("confirm", safetyFacade.lastSavedRequest?.automationModeId)
    assertEquals("lightweight", savedSafety["liveContextModeId"])
    assertEquals(false, savedSafety["memoryToolsEnabled"])
    assertEquals(1, chatSnapshotNotificationCount)
    assertNull(delegate.lastSafetyAutomationModeId)
    assertEquals("delegate-safety", delegate.loadSafetySettings()["source"])
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesLlmAndMcpWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val llmFacade = RecordingLlmConfigFacade()
    val mcpFacade = RecordingMcpSettingsFacade()
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .createForTest(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = llmFacade,
      mcpSettingsFacade = mcpFacade,
    )

    val llm = gateway.loadLlmConfig()
    val savedLlm = gateway.saveLlmConfig(
      enabled = true,
      providerMode = LlmProviderModes.CLOUD,
      providerId = "custom",
      selectedProviderOptionId = "custom-provider",
      protocol = "anthropic",
      providerName = "Custom",
      providerNotes = "Notes",
      baseUrl = "https://example.com",
      apiKey = "secret",
      model = "kimi-k2.5",
      reasoningEffort = "medium",
      systemPrompt = "Prompt",
      contextBudgetPreset = "expanded",
      contextBudgetReservedOutputTokens = 3072,
      contextBudgetSafetyMarginTokens = 1536,
      contextBudgetEffectiveInputPercent = 0.92,
    )
    val validatedLlm = gateway.validateLlmConfig(
      providerId = "custom",
      protocol = "anthropic",
      baseUrl = "https://example.com",
      apiKey = "secret",
      model = "kimi-k2.5",
      reasoningEffort = "medium",
    )
    val savedCustomProvider = gateway.saveCustomLlmProvider(
      selectedProviderOptionId = "custom-provider-2",
      protocol = "anthropic",
      providerName = "Custom 2",
      providerNotes = "More notes",
      baseUrl = "https://provider.example.com",
      apiKey = "secret-2",
      model = "claude-kimi-hybrid",
      reasoningEffort = "high",
      systemPrompt = "Prompt 2",
      contextBudgetPreset = "compact",
      contextBudgetReservedOutputTokens = 2048,
    )
    val mcp = gateway.loadMcpSettings()
    val mcpMaster = gateway.setMcpMasterEnabled(false)
    val mcpServer = gateway.setMcpServerEnabled(serverId = "filesystem", enabled = true)

    assertEquals("kimi-k2.5", llm["model"])
    assertEquals("anthropic", savedLlm["protocol"])
    assertEquals("custom-provider", llmFacade.lastSavedRequest?.selectedProviderOptionId)
    assertEquals("expanded", llmFacade.lastSavedRequest?.contextBudgetPreset)
    assertEquals(3072, llmFacade.lastSavedRequest?.contextBudgetReservedOutputTokens)
    assertEquals(1536, llmFacade.lastSavedRequest?.contextBudgetSafetyMarginTokens)
    assertEquals(0.92, llmFacade.lastSavedRequest?.contextBudgetEffectiveInputPercent)
    assertEquals("custom-provider-2", llmFacade.lastCustomProviderRequest?.selectedProviderOptionId)
    assertEquals("compact", llmFacade.lastCustomProviderRequest?.contextBudgetPreset)
    assertEquals(2048, llmFacade.lastCustomProviderRequest?.contextBudgetReservedOutputTokens)
    assertEquals("expanded", savedLlm["contextBudgetPreset"])
    assertEquals(3072, savedLlm["contextBudgetReservedOutputTokens"])
    assertEquals("claude-kimi-hybrid", savedCustomProvider["model"])
    assertEquals("kimi-k2.5", llmFacade.lastValidatedRequest?.model)
    assertEquals(true, (validatedLlm["agentCapability"] as Map<String, Any?>)["nativeToolCallingAvailable"])
    assertEquals("MCP", mcp["title"])
    assertEquals(false, mcpMaster["masterEnabled"])
    assertEquals("filesystem", mcpFacade.lastServerEnabledId)
    assertEquals(true, mcpFacade.lastServerEnabledValue)
    assertEquals(true, (mcpServer["servers"] as List<Map<String, Any?>>).first()["isActionEnabled"])
    assertEquals(null, delegate.lastMcpMasterEnabled)
    assertEquals("delegate-llm", delegate.loadLlmConfig()["source"])
  }

  @Test
  fun serviceOwnedSettingsGatewayRefreshesChatAndWarmupAfterSavingLlmConfig() {
    val llmFacade = RecordingLlmConfigFacade()
    val warmupAccess = RecordingOnDeviceWarmupAccess()
    var chatSnapshotNotificationCount = 0
    val gateway = ServiceOwnedSettingsGateway(
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .createForTest(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      appLanguageSettingsAccess = RecordingAppLanguageSettingsGatewayAccess(),
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = llmFacade,
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      chatSnapshotNotifier = { chatSnapshotNotificationCount += 1 },
      onDeviceWarmupAccess = warmupAccess,
    )

    gateway.saveLlmConfig(
      enabled = true,
      providerMode = LlmProviderModes.ON_DEVICE_MODEL,
      providerId = "on-device",
      selectedProviderOptionId = "on-device",
      protocol = "openai",
      providerName = "On device",
      providerNotes = "",
      baseUrl = "",
      apiKey = "",
      model = "",
      reasoningEffort = "medium",
      systemPrompt = "Prompt",
      selectedOnDeviceModelId = "gemma3n-e2b-it-int4",
      onDeviceAccelerator = OnDeviceLlmAccelerators.GPU,
    )

    assertEquals(1, warmupAccess.ensureWarmForActiveSessionCallCount)
    assertEquals(1, chatSnapshotNotificationCount)
  }

  @Test
  fun serviceOwnedSkillsGatewayHandlesSimpleFacadeBackedFlowsWithoutDelegateRoundTrip() {
    val delegate = RecordingSkillsGateway("delegate")
    val facade = RecordingServiceOwnedSkillsFacade()
    var notifiedSnapshotCount = 0
    val observedSources = mutableListOf<List<String>>()
    val gateway = ServiceOwnedSkillsGateway(
      delegate = delegate,
      skillsFacade = facade,
      localeTag = "en",
      skillInstalled = { skillId -> "Installed $skillId." },
      skillRemoved = { skillId -> "Removed $skillId." },
      skillsReloaded = "Reloaded skills.",
      snapshotNotifier = { notifiedSnapshotCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val disposer = gateway.observeSkills { snapshot ->
      observedSources += (snapshot["installedSkills"] as List<Map<String, Any?>>).mapNotNull { skill ->
        skill["id"] as String?
      }
    }

    val snapshot = gateway.loadSkillsSnapshot(query = "voice", suggestedLimit = 6)
    gateway.setSkillEnabled("voice-notes", enabled = false)
    val inspect = gateway.inspectSkillSource("github:opencray/skills")
    val instructions = gateway.loadSkillInstructions("voice-notes")
    val suggestedInstructions = gateway.loadSuggestedSkillInstructions(
      sourceRef = "github:opencray/skills",
      selectedSkillName = "voice-notes",
    )
    val installed = gateway.installSkillSource(
      sourceRef = "github:opencray/skills",
      selectedSkillName = "voice-notes",
    )
    val batchInstalled = gateway.installSkillSourceBatch(
      sourceRef = "github:opencray/skills",
      selectedSkillNames = listOf("voice-notes", "git-sync"),
    )
    val checked = gateway.checkInstalledSkillUpdates("voice-notes")
    val updated = gateway.updateInstalledSkill("voice-notes")
    val removed = gateway.deleteInstalledSkill("voice-notes")
    val refreshed = gateway.refreshSkills()
    val activation = gateway.activateSkillsInstallSource("github-url")

    val installedSkills = snapshot["installedSkills"] as List<Map<String, Any?>>
    val installSources = snapshot["installSources"] as List<Map<String, Any?>>
    assertEquals("voice-notes", installedSkills.first()["id"])
    assertEquals("github-url", installSources.first()["id"])
    assertTrue(facade.loadQueries.contains("voice"))
    assertTrue(facade.loadSuggestedLimits.contains(6))
    assertEquals("", facade.lastLoadQuery)
    assertEquals(0, facade.lastLoadSuggestedLimit)
    assertEquals("voice-notes", facade.lastSetSkillEnabledSkillId)
    assertEquals(false, facade.lastSetSkillEnabledValue)
    assertEquals("github:opencray/skills", facade.lastInstalledSourceRef)
    assertEquals("voice-notes", facade.lastInstalledSelectedSkillName)
    assertEquals("github:opencray/skills", facade.lastBatchInstalledSourceRef)
    assertEquals(listOf("voice-notes", "git-sync"), facade.lastBatchInstalledSkillNames)
    assertEquals("voice-notes", facade.lastCheckedSkillId)
    assertEquals("voice-notes", facade.lastUpdatedSkillId)
    assertEquals("github:opencray/skills", inspect["sourceRef"])
    assertEquals("voice-notes", instructions["id"])
    assertEquals("github:opencray/skills", suggestedInstructions["sourceDirectoryPath"])
    assertEquals("Installed voice-notes.", installed)
    assertEquals("Installed 2 skills.", batchInstalled)
    assertEquals("Update available for 'voice-notes'.", checked)
    assertEquals("Updated 'voice-notes'.", updated)
    assertEquals("Removed voice-notes.", removed)
    assertEquals("Reloaded skills.", refreshed)
    assertEquals("activated:github-url", activation)
    assertEquals(6, notifiedSnapshotCount)
    assertEquals(null, delegate.lastInstalledSourceRef)
    assertEquals(null, delegate.lastSetSkillEnabledSkillId)
    assertEquals(null, delegate.lastDeletedSkillId)
    assertEquals(0, delegate.refreshCount)
    assertEquals(null, delegate.lastActivatedSourceId)
    assertEquals(0, delegate.observeSkillsCount)
    assertEquals(7, observedSources.size)
    disposer()
  }

  @Test
  fun androidBindingClientTransitionsFromFallbackBindingToBinderConnection() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client"))
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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
  fun androidBindingClientDefaultPeekSnapshotReturnsNullWithoutBinderOrFallbackSnapshot() {
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    val snapshot = client.peekSnapshot()

    assertNull(snapshot)
    assertEquals(0, startRequestCount)
    assertEquals(0, bindingAdapter.bindCount)
  }

  @Test
  fun runtimeServiceProjectionStoreRoundTripsSnapshot() {
    val expected = bridgeSnapshot(
      temporaryFolder.newFolder("runtime-service-projection-store"),
    ).copy(
      serviceKeepAliveState = RuntimeServiceKeepAliveState(
        phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
        idleGraceMs = 60_000L,
        stopScheduled = true,
        stopDeadlineEpochMs = 61_000L,
        lastStartId = 9,
        changedAtEpochMs = 1_500L,
      ),
    ).toProjectionSnapshot()
    val store = FileBackedRuntimeServiceProjectionStoreFactory(
      temporaryFolder.newFolder("runtime-service-projection-store-files"),
    ).create()

    store.saveSnapshot(expected)

    val actual = store.loadSnapshot()

    assertNotNull(actual)
    assertEquals(
      expected.runtimeOwnerLifecycle.runtimeOwnerId,
      actual?.runtimeOwnerLifecycle?.runtimeOwnerId,
    )
    assertEquals(
      expected.runtimeOwnerWorkSummary.activeSessionIds,
      actual?.runtimeOwnerWorkSummary?.activeSessionIds,
    )
    assertEquals(
      expected.serviceLifecycle.serviceInstanceId,
      actual?.serviceLifecycle?.serviceInstanceId,
    )
    assertEquals(
      expected.serviceWorkState.keepAliveReason,
      actual?.serviceWorkState?.keepAliveReason,
    )
    assertEquals(
      expected.serviceKeepAliveState.stopDeadlineEpochMs,
      actual?.serviceKeepAliveState?.stopDeadlineEpochMs,
    )
  }

  @Test
  fun androidBindingClientPeekProjectionSnapshotUsesDurableStoreWithoutStartingServiceOrBind() {
    val expected = bridgeSnapshot(
      temporaryFolder.newFolder("binding-client-projection-peek"),
    ).toProjectionSnapshot()
    val bindingAdapter = RecordingBindingAdapter()
    val projectionStore = inMemoryRuntimeServiceProjectionStore().apply {
      saveSnapshot(expected)
    }
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      fallbackBridge = object : OpenCrayRuntimeServiceBridge {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
          error("fallback bridge should not be used for projection peeks")
      },
      projectionStore = projectionStore,
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    val snapshot = client.peekProjectionSnapshot()

    assertEquals(
      expected.runtimeOwnerWorkSummary.activeSessionIds,
      snapshot?.runtimeOwnerWorkSummary?.activeSessionIds,
    )
    assertEquals(
      expected.serviceKeepAliveState.phase,
      snapshot?.serviceKeepAliveState?.phase,
    )
    assertEquals(0, startRequestCount)
    assertEquals(0, bindingAdapter.bindCount)
  }

  @Test
  fun androidBindingClientExposesBinderSkillsGatewayWhenConnected() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-skills-gateway"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingSkillsGateway("binder")
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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
      fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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
  fun serviceOwnedShellGatewayLoadsAndObservesWithoutHostRuntimeDelegate() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("service-owned-shell"))
    var keepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
      changedAtEpochMs = 1_000L,
    )
    var keepAliveListener: (() -> Unit)? = null
    val gateway = ServiceOwnedShellGateway(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      localeTag = "en",
      hostLabel = "HOST",
      hostSummary = "summary",
      runtimeHostAccess = expected.runtimeAccess.hostAccess,
      runtimeServiceLifecycle = expected.serviceLifecycle,
      runtimeServiceWorkStateProvider = { expected.serviceWorkState },
      runtimeServiceKeepAliveStateProvider = { keepAliveState },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
        keepAliveListener = listener
        { if (keepAliveListener === listener) keepAliveListener = null }
      },
      runtimeServiceConnectionStateProvider = { RuntimeServiceConnectionState.binderConnected() },
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedStates = mutableListOf<Pair<String?, String?>>()

    val disposer = gateway.observeShell { snapshot ->
      val keepAlive = snapshot["runtimeServiceKeepAliveState"] as? Map<*, *>
      observedStates += (snapshot["localeTag"] as String?) to (keepAlive?.get("phase") as String?)
    }

    try {
      val initial = gateway.loadShellSnapshot()

      assertEquals("chat", initial["initialTab"])
      assertEquals("en", initial["localeTag"])
      assertNotNull(initial["runtimeOwnerLifecycle"])
      assertNotNull(initial["runtimeOwnerWorkSummary"])
      assertNotNull(initial["runtimeServiceLifecycle"])
      assertEquals(listOf("en" to RuntimeServiceKeepAliveState.PHASE_CREATED), observedStates)

      gateway.updateLocalizedResources(
        localeTag = "zh-CN",
        hostLabel = "HOST-ZH",
        hostSummary = "summary-zh",
      )
      gateway.emitLocalizedSnapshotChanged()

      keepAliveState = keepAliveState.copy(
        phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
        changedAtEpochMs = 1_500L,
      )
      keepAliveListener?.invoke()

      assertEquals(
        listOf(
          "en" to RuntimeServiceKeepAliveState.PHASE_CREATED,
          "zh-CN" to RuntimeServiceKeepAliveState.PHASE_CREATED,
          "zh-CN" to RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
        ),
        observedStates,
      )
    } finally {
      disposer()
    }
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayKeepsHostChatAndRuntimeSnapshotsAndUsesProjectionForDebugReads() {
    val delegate = RecordingChatRuntimeGateway("delegate")
    val readGateway = RecordingChatRuntimeGateway("projection")
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = readGateway,
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedChatSources = mutableListOf<String?>()
    val observedRuntimeSources = mutableListOf<String?>()

    val chatDisposer = gateway.observeChat { snapshot ->
      observedChatSources += snapshot["source"] as String?
    }
    val runtimeDisposer = gateway.observeChatRuntime { snapshot ->
      observedRuntimeSources += snapshot["source"] as String?
    }

    try {
      assertEquals("delegate-chat", gateway.loadChatSnapshot()["source"])
      assertEquals("delegate-runtime", gateway.loadChatRuntimeSnapshot()["source"])
      assertEquals("delegate-run", gateway.loadChatRunSnapshot("run-alpha")?.get("source"))
      assertEquals(
        "projection-memory-action",
        gateway.applyMemoryDebugAction("memory-1", "suppress")["source"],
      )

      gateway.submitChatMessage("hello", emptyList())
      gateway.notifyChatSnapshotsChanged()

      assertEquals(listOf("delegate-chat", "delegate-chat"), observedChatSources)
      assertEquals(listOf("delegate-runtime", "delegate-runtime"), observedRuntimeSources)
      assertEquals("hello", delegate.submittedText)
      assertNull(delegate.memoryDebugActionRecordId)
      assertEquals("memory-1", readGateway.memoryDebugActionRecordId)
      assertEquals("suppress", readGateway.memoryDebugActionId)
      assertNull(readGateway.submittedText)
      assertEquals(1, delegate.notifiedChatSnapshotCount)
    } finally {
      chatDisposer()
      runtimeDisposer()
    }
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayRoutesSessionMutationsThroughServiceAccess() {
    val chatRoot = temporaryFolder.newFolder("service-owned-chat-mutation-store")
    val chatSessionStore = ChatSessionLocalStore(chatRoot.resolve("chat-session"))
    val originalSessionId = chatSessionStore.loadState().activeSession.sessionId
    chatSessionStore.appendUserMessage(originalSessionId, "Keep this transcript")
    val sourceMessageId = requireNotNull(
      chatSessionStore.loadSession(originalSessionId),
    ).messages.last().messageId
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    val repairCalls = mutableListOf<Pair<String, List<AgentRunSnapshot>>>()
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val delegate = RecordingChatRuntimeGateway("delegate")
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = RecordingChatRuntimeGateway("projection"),
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      chatSessionMutationAccess = ServiceOwnedChatSessionMutationAccess(
        chatSessionStore = chatSessionStore,
        runtimeHostAccess = DefaultOpenCrayRuntimeHostAccess(
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
        chatUnreadMessageState = ChatUnreadMessageState(),
        terminalReplayRepairer = { sessionId, runs ->
          repairCalls += sessionId to runs
        },
      ),
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    gateway.createChatSession()
    val createdSessionId = chatSessionStore.loadState().activeSession.sessionId

    gateway.copyChatSession(originalSessionId)
    val copiedSessionId = chatSessionStore.loadState().activeSession.sessionId

    gateway.branchChatSessionFromMessage(originalSessionId, sourceMessageId)
    val branchedSessionId = chatSessionStore.loadState().activeSession.sessionId

    gateway.branchChatSessionFromMessage(originalSessionId, "")

    assertTrue(createdSessionId != originalSessionId)
    assertTrue(copiedSessionId != originalSessionId)
    assertTrue(copiedSessionId != createdSessionId)
    assertTrue(branchedSessionId != originalSessionId)
    assertTrue(branchedSessionId != copiedSessionId)
    assertEquals(
      sourceMessageId,
      requireNotNull(chatSessionStore.loadSession(branchedSessionId)).messages.last().messageId,
    )
    assertEquals(0, delegate.createChatSessionCallCount)
    assertTrue(delegate.copiedSessionIds.isEmpty())
    assertTrue(delegate.branchedSessionRequests.isEmpty())
    assertEquals(3, delegate.notifiedChatSnapshotCount)
    assertEquals(
      listOf(createdSessionId, copiedSessionId, branchedSessionId),
      runtimeManager.resumedSessionIds,
    )
    assertEquals(
      listOf(
        createdSessionId to emptyList<AgentRunSnapshot>(),
        copiedSessionId to emptyList(),
        branchedSessionId to emptyList(),
      ),
      repairCalls,
    )
  }

  @Test
  fun serviceOwnedChatSessionMutationAccessClearsSharedUnreadStateWhenReusingEmptySession() {
    val chatRoot = temporaryFolder.newFolder("service-owned-chat-unread-store")
    val chatSessionStore = ChatSessionLocalStore(chatRoot.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val chatUnreadMessageState = ChatUnreadMessageState().apply {
      incrementIfBackgroundUpdate(
        sessionId = activeSessionId,
        activeSessionId = "other-session",
        text = "background update",
      )
    }
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    val repairCalls = mutableListOf<Pair<String, List<AgentRunSnapshot>>>()
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val access = ServiceOwnedChatSessionMutationAccess(
      chatSessionStore = chatSessionStore,
      runtimeHostAccess = DefaultOpenCrayRuntimeHostAccess(
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
      chatUnreadMessageState = chatUnreadMessageState,
      terminalReplayRepairer = { sessionId, runs ->
        repairCalls += sessionId to runs
      },
    )

    assertEquals(1, chatUnreadMessageState.rawCount(activeSessionId))

    access.createChatSession()

    assertEquals(activeSessionId, chatSessionStore.loadState().activeSession.sessionId)
    assertEquals(0, chatUnreadMessageState.rawCount(activeSessionId))
    assertEquals(listOf(activeSessionId), runtimeManager.resumedSessionIds)
    assertEquals(
      listOf(activeSessionId to emptyList<AgentRunSnapshot>()),
      repairCalls,
    )
  }

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
  fun serviceBackedChatRuntimeGatewayAllowsFallbackLoadsButRequiresBinderForCommands() {
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = binderGateway,
      dispatchChatWriteCommandHandler = binderGateway::dispatchChatWriteCommand,
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
    serviceClient.dispatchChatWriteCommandHandler = null

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
  fun serviceBackedGatewayObserversKeepRuntimeStickyAfterBinderGatewayAppears() {
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      dispatchChatWriteCommandHandler = null,
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
    serviceClient.dispatchChatWriteCommandHandler = binderGateway::dispatchChatWriteCommand
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-chat", "binder-chat"), observedChatSources)
    assertEquals(listOf("fallback-runtime", "binder-runtime"), observedRuntimeSources)

    serviceClient.currentChatGateway = null
    serviceClient.dispatchChatWriteCommandHandler = null
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-chat", "binder-chat", "fallback-chat"), observedChatSources)
    assertEquals(
      listOf("fallback-runtime", "binder-runtime"),
      observedRuntimeSources,
    )
  }

  @Test
  fun serviceBackedChatRuntimeGatewayObserveChatRuntimeStaysOnBinderAcrossTransientPeekMiss() {
    val binderGateway = RecordingChatRuntimeGateway("binder").apply {
      chatRuntimePayload = mapOf(
        "source" to "binder-runtime",
        "revision" to 1,
      )
    }
    val fallbackGateway = RecordingChatRuntimeGateway("fallback").apply {
      chatRuntimePayload = mapOf(
        "source" to "fallback-runtime",
        "revision" to 0,
      )
    }
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = binderGateway,
      dispatchChatWriteCommandHandler = binderGateway::dispatchChatWriteCommand,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()
    val observedRevisions = mutableListOf<Int?>()

    val disposer = gateway.observeChatRuntime { snapshot ->
      observedSources += snapshot["source"] as String?
      observedRevisions += snapshot["revision"] as Int?
    }

    binderGateway.chatRuntimePayload = mapOf(
      "source" to "binder-runtime",
      "revision" to 2,
    )
    binderGateway.emitChatRuntimeSnapshot()
    serviceClient.currentChatGateway = null
    fallbackGateway.chatRuntimePayload = mapOf(
      "source" to "fallback-runtime",
      "revision" to 10,
    )
    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.bindingPending())
    binderGateway.chatRuntimePayload = mapOf(
      "source" to "binder-runtime",
      "revision" to 3,
    )
    binderGateway.emitChatRuntimeSnapshot()
    disposer()

    assertEquals(
      listOf("fallback-runtime", "binder-runtime", "binder-runtime", "binder-runtime"),
      observedSources,
    )
    assertEquals(listOf(0, 1, 2, 3), observedRevisions)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayWaitsForPendingBinderBeforeSubmittingMessage() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("chat-gateway-await-binder"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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

        override fun dispatchChatWriteCommand(
          command: OpenCrayChatWriteCommand,
        ): OpenCrayChatWriteDispatchResult = binderGateway.dispatchChatWriteCommand(command)
      },
    )
    worker.join(1_000L)

    assertFalse(worker.isAlive)
    assertEquals(null, failure)
    assertEquals("hello", binderGateway.submittedText)
    assertEquals("binder-submit", result?.get("source"))
  }

  @Test
  fun serviceBackedChatRuntimeGatewayWaitsForPendingBinderBeforeLoadingRuntimeSnapshot() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("chat-gateway-await-runtime-read"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder").apply {
      chatRuntimePayload = mapOf(
        "source" to "binder-runtime",
        "sessionId" to "session-stream",
        "liveAssistantDrafts" to listOf(
          mapOf(
            "runId" to "run-stream",
            "taskId" to "task-stream",
            "pendingMessageId" to "pending-stream",
            "text" to "Streaming answer",
            "updatedAtEpochMs" to 1_234L,
          ),
        ),
      )
    }
    val fallbackGateway = RecordingChatRuntimeGateway("fallback").apply {
      chatRuntimePayload = mapOf(
        "source" to "fallback-runtime",
        "sessionId" to "session-stream",
        "liveAssistantDrafts" to emptyList<Map<String, Any?>>(),
      )
    }
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = fallbackGateway,
    )
    var result: Map<String, Any?>? = null
    var failure: Throwable? = null

    val worker = Thread {
      runCatching {
        gateway.loadChatRuntimeSnapshot()
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

    @Suppress("UNCHECKED_CAST")
    val liveDrafts = result?.get("liveAssistantDrafts") as? List<Map<String, Any?>>

    assertFalse(worker.isAlive)
    assertEquals(null, failure)
    assertEquals("binder-runtime", result?.get("source"))
    assertEquals(1, liveDrafts?.size)
    assertEquals("Streaming answer", liveDrafts?.single()?.get("text"))
  }

  @Test
  fun serviceBackedChatRuntimeGatewayWritesThroughDispatchWithoutBinderReadGateway() {
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      dispatchChatWriteCommandHandler = binderGateway::dispatchChatWriteCommand,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    gateway.approveChatApproval("run-alpha")

    assertEquals("run-alpha", binderGateway.approvedTaskIdOrRunId)
    assertNull(fallbackGateway.approvedTaskIdOrRunId)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayApprovesForSessionThroughDispatchWithoutBinderReadGateway() {
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      dispatchChatWriteCommandHandler = binderGateway::dispatchChatWriteCommand,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    gateway.approveChatApprovalForSession("run-beta")

    assertEquals("run-beta", binderGateway.approvedForSessionTaskIdOrRunId)
    assertNull(fallbackGateway.approvedForSessionTaskIdOrRunId)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayObserversRecheckGatewayAfterConnectionObservationStarts() {
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = ChatGatewayAvailableOnObserveClient(binderGateway),
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

    assertEquals(listOf("fallback-chat", "binder-chat"), observedChatSources)
    assertEquals(listOf("fallback-runtime", "binder-runtime"), observedRuntimeSources)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayObserveChatFallsBackAfterBinderDisconnectAndRebinds() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("chat-gateway-observe-chat-disconnect"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder").apply {
      chatPayload = mapOf(
        "source" to "binder-chat",
        "revision" to 1,
      )
    }
    val reboundGateway = RecordingChatRuntimeGateway("binder-rebound").apply {
      chatPayload = mapOf(
        "source" to "binder-rebound-chat",
        "revision" to 2,
      )
    }
    val fallbackGateway = RecordingChatRuntimeGateway("fallback").apply {
      chatPayload = mapOf(
        "source" to "fallback-chat",
        "revision" to 0,
      )
    }
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()
    val observedRevisions = mutableListOf<Int?>()

    val disposer = gateway.observeChat { snapshot ->
      observedSources += snapshot["source"] as String?
      observedRevisions += snapshot["revision"] as Int?
    }

    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = binderGateway
      },
    )
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "binder-chat"
    }
    fallbackGateway.chatPayload = mapOf(
      "source" to "fallback-chat",
      "revision" to 10,
    )
    bindingAdapter.disconnect()
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "fallback-chat" &&
        observedRevisions.lastOrNull() == 10
    }
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 2 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = reboundGateway
      },
    )
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "binder-rebound-chat" &&
        observedRevisions.lastOrNull() == 2
    }
    disposer()

    assertEquals(
      listOf("fallback-chat", "binder-chat", "fallback-chat", "binder-rebound-chat"),
      observedSources,
    )
    assertEquals(listOf(0, 1, 10, 2), observedRevisions)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayObserveChatRuntimeStaysStickyAcrossDisconnectButRebindsOnReconnect() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("chat-gateway-observe-runtime-disconnect"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder").apply {
      chatRuntimePayload = mapOf(
        "source" to "binder-runtime",
        "sessionId" to "session-stream",
        "liveAssistantDrafts" to listOf(
          mapOf(
            "runId" to "run-stream",
            "taskId" to "task-stream",
            "pendingMessageId" to "pending-stream",
            "text" to "Streaming answer",
            "updatedAtEpochMs" to 1_234L,
          ),
        ),
      )
    }
    val reboundGateway = RecordingChatRuntimeGateway("binder-rebound").apply {
      chatRuntimePayload = mapOf(
        "source" to "binder-rebound-runtime",
        "sessionId" to "session-stream",
        "liveAssistantDrafts" to listOf(
          mapOf(
            "runId" to "run-stream-2",
            "taskId" to "task-stream-2",
            "pendingMessageId" to "pending-stream-2",
            "text" to "Streaming answer rebound",
            "updatedAtEpochMs" to 1_240L,
          ),
        ),
      )
    }
    val fallbackGateway = RecordingChatRuntimeGateway("fallback").apply {
      chatRuntimePayload = mapOf(
        "source" to "fallback-runtime",
        "sessionId" to "session-stream",
        "liveAssistantDrafts" to emptyList<Map<String, Any?>>(),
      )
    }
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()
    val observedDraftCounts = mutableListOf<Int>()

    val disposer = gateway.observeChatRuntime { snapshot ->
      observedSources += snapshot["source"] as String?
      observedDraftCounts += ((snapshot["liveAssistantDrafts"] as? List<*>)?.size ?: -1)
    }

    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = binderGateway
      },
    )
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "binder-runtime"
    }
    bindingAdapter.disconnect()
    Thread.sleep(100L)
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 2 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = reboundGateway
      },
    )
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "binder-rebound-runtime" &&
        observedDraftCounts.lastOrNull() == 1
    }
    disposer()

    assertEquals(
      listOf("fallback-runtime", "binder-runtime", "binder-rebound-runtime"),
      observedSources,
    )
    assertEquals(listOf(0, 1, 1), observedDraftCounts)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayObserveLiveDraftEventsStaysStickyAcrossDisconnectButRebindsOnReconnect() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("chat-gateway-observe-draft-disconnect"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder").apply {
      liveAssistantDraftEventPayload = mapOf(
        "source" to "binder-draft",
        "sessionId" to "session-stream",
        "runId" to "run-stream",
        "taskId" to "task-stream",
        "pendingMessageId" to "pending-stream",
        "text" to "Streaming answer",
        "updatedAtEpochMs" to 1_234L,
        "cleared" to false,
      )
    }
    val reboundGateway = RecordingChatRuntimeGateway("binder-rebound").apply {
      liveAssistantDraftEventPayload = mapOf(
        "source" to "binder-rebound-draft",
        "sessionId" to "session-stream",
        "runId" to "run-stream",
        "taskId" to "task-stream",
        "pendingMessageId" to "pending-stream",
        "text" to "Streaming answer rebound",
        "updatedAtEpochMs" to 1_236L,
        "cleared" to false,
      )
    }
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()
    val observedClears = mutableListOf<Boolean?>()

    val disposer = gateway.observeLiveAssistantDraftEvents { payload ->
      observedSources += payload["source"] as String?
      observedClears += payload["cleared"] as Boolean?
    }

    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = binderGateway
      },
    )
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "binder-draft"
    }
    fallbackGateway.liveAssistantDraftEventPayload = mapOf(
      "source" to "fallback-draft",
      "sessionId" to "session-stream",
      "runId" to "run-stream",
      "taskId" to "task-stream",
      "pendingMessageId" to "pending-stream",
      "text" to "",
      "updatedAtEpochMs" to 1_235L,
      "cleared" to true,
    )
    bindingAdapter.disconnect()
    Thread.sleep(100L)
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 2 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = reboundGateway
      },
    )
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "binder-rebound-draft"
    }
    disposer()

    assertEquals(listOf("binder-draft", "binder-rebound-draft"), observedSources)
    assertEquals(listOf(false, false), observedClears)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayWaitForRunUsesRemainingCallerTimeoutAfterBinderAwait() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("chat-gateway-wait-run-timeout-budget"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = RecordingChatRuntimeGateway("fallback"),
    )
    var result: Map<String, Any?>? = null

    val worker = Thread {
      result = gateway.waitForChatRun("run-stream", 150L)
    }

    worker.start()
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    Thread.sleep(75L)
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = binderGateway
      },
    )
    worker.join(1_000L)

    val remainingTimeoutMs = (result?.get("timeoutMs") as? Number)?.toLong() ?: -1L

    assertFalse(worker.isAlive)
    assertTrue(remainingTimeoutMs >= 0L && remainingTimeoutMs < 150L)
  }

  @Test
  fun serviceBackedSkillsGatewayAllowsFallbackLoadsButRequiresBinderForCommands() {
    val binderGateway = RecordingSkillsGateway("binder")
    val fallbackGateway = RecordingSkillsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = binderGateway,
      dispatchSkillsWriteCommandHandler = binderGateway::dispatchSkillsWriteCommand,
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
    serviceClient.dispatchSkillsWriteCommandHandler = null

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
  fun serviceBackedSkillsGatewayWritesThroughDispatchWithoutBinderReadGateway() {
    val binderGateway = RecordingSkillsGateway("binder")
    val fallbackGateway = RecordingSkillsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
      dispatchSkillsWriteCommandHandler = binderGateway::dispatchSkillsWriteCommand,
    )
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals(
      "fallback-skills",
      gateway.loadSkillsSnapshot(query = "", suggestedLimit = 0)["source"],
    )
    assertEquals(
      "Installed roin-orca/skills via binder.",
      gateway.installSkillSource(sourceRef = "roin-orca/skills", selectedSkillName = ""),
    )
    assertEquals("roin-orca/skills", binderGateway.lastInstalledSourceRef)
    assertNull(fallbackGateway.lastInstalledSourceRef)
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
  fun serviceBackedSkillsGatewayWaitsForPendingBinderBeforeInstallingSource() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("skills-gateway-await-binder"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingSkillsGateway("binder")
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = RecordingSkillsGateway("fallback"),
    )
    var result: String? = null
    var failure: Throwable? = null

    val worker = Thread {
      runCatching {
        gateway.installSkillSource(sourceRef = "roin-orca/skills", selectedSkillName = "")
      }.onSuccess { message ->
        result = message
      }.onFailure { throwable ->
        failure = throwable
      }
    }

    worker.start()
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun dispatchSkillsWriteCommand(
          command: OpenCraySkillsWriteCommand,
        ): OpenCraySkillsWriteDispatchResult = binderGateway.dispatchSkillsWriteCommand(command)
      },
    )
    worker.join(1_000L)

    assertFalse(worker.isAlive)
    assertEquals(null, failure)
    assertEquals("roin-orca/skills", binderGateway.lastInstalledSourceRef)
    assertEquals("Installed roin-orca/skills via binder.", result)
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
    assertNull(gateway.loadChatRunSnapshot(runId))
    assertNull(gateway.waitForChatRun(runId, 0))
    assertEquals("Restored", summary["body"])
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

  @Test
  fun serviceBackedSettingsGatewayAllowsFallbackLoadsButRequiresBinderForCommands() {
    val binderGateway = RecordingSettingsGateway("binder")
    val fallbackGateway = RecordingSettingsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = binderGateway,
      dispatchSettingsWriteCommandHandler = binderGateway::dispatchSettingsWriteCommand,
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
    serviceClient.dispatchSettingsWriteCommandHandler = null

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
  fun serviceBackedSettingsGatewayAllowsFallbackStrongBackgroundLoadsButRequiresBinderForActions() {
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
    val failure = runCatching {
      gateway.performStrongBackgroundAction(
        StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      )
    }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message?.contains("performStrongBackgroundAction") == true)
    assertTrue(failure?.message?.contains("phase=fallback") == true)
    assertNull(fallbackGateway.lastStrongBackgroundActionId)

    serviceClient.currentSettingsGateway = binderGateway
    serviceClient.dispatchSettingsWriteCommandHandler = binderGateway::dispatchSettingsWriteCommand

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
  fun serviceBackedSettingsGatewayWritesStrongBackgroundActionThroughDispatchWithoutBinderReadGateway() {
    val binderGateway = RecordingSettingsGateway("binder")
    val fallbackGateway = RecordingSettingsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
      dispatchSettingsWriteCommandHandler = binderGateway::dispatchSettingsWriteCommand,
    )
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals("fallback-strong-background", gateway.loadStrongBackgroundSnapshot()["source"])
    assertEquals(
      "binder-strong-background-action",
      gateway.performStrongBackgroundAction(
        StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      )["source"],
    )
    assertEquals(
      StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      binderGateway.lastStrongBackgroundActionId,
    )
    assertNull(fallbackGateway.lastStrongBackgroundActionId)
  }

  @Test
  fun serviceBackedSettingsGatewayWaitsForPendingBinderBeforeSavingCustomProvider() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("settings-gateway-await-binder"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingSettingsGateway("binder")
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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

        override fun dispatchSettingsWriteCommand(
          command: OpenCraySettingsWriteCommand,
        ): OpenCraySettingsWriteDispatchResult = binderGateway.dispatchSettingsWriteCommand(command)
      },
    )
    worker.join(1_000L)

    assertFalse(worker.isAlive)
    assertEquals(null, failure)
    assertEquals("binder-custom-llm", result?.get("source"))
  }

  @Test
  fun serviceBackedSettingsGatewayWaitsForPendingBinderBeforePerformingStrongBackgroundAction() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("settings-gateway-await-strong-background"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingSettingsGateway("binder")
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        fallbackBridge = InProcessOpenCrayRuntimeServiceBridge { expected },
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
        gateway.performStrongBackgroundAction(
          StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
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

        override fun dispatchSettingsWriteCommand(
          command: OpenCraySettingsWriteCommand,
        ): OpenCraySettingsWriteDispatchResult = binderGateway.dispatchSettingsWriteCommand(command)
      },
    )
    worker.join(1_000L)

    assertFalse(worker.isAlive)
    assertEquals(null, failure)
    assertEquals(
      StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      binderGateway.lastStrongBackgroundActionId,
    )
    assertEquals("binder-strong-background-action", result?.get("source"))
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
    private val queueSnapshot: SessionQueueSnapshot? = null,
    private val hasPendingWork: Boolean = false,
    private val hasLiveManagedProcesses: Boolean = false,
    private val subAgentHandles: List<SubAgentHandleState> = emptyList(),
    private val cancelRequestResult: Boolean = false,
    private val resumeRequestResult: Boolean = false,
  ) : AgentSessionHandle {
    val submittedTasks = mutableListOf<AgentTask>()
    val detachedControlTasks = mutableListOf<AgentTask>()
    val cancelledTaskIds = mutableListOf<String>()
    val resumedTaskIds = mutableListOf<String>()
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

    override fun submitDetachedControlTask(task: AgentTask): AgentRunSubmission {
      detachedControlTasks += task
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

    override fun requestCancel(taskId: String): Boolean {
      cancelledTaskIds += taskId
      return cancelRequestResult
    }

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean {
      resumedTaskIds += taskId
      return resumeRequestResult
    }

    override fun listRuns(): List<AgentRunSnapshot> = runs

    override fun findRun(runId: String): AgentRunSnapshot? = runs.firstOrNull { run -> run.runId == runId }

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState {
      resumedSessionIds += sessionId
      return SessionLifecycleState.IDLE
    }

    override fun snapshot(): SessionQueueSnapshot = queueSnapshot ?: SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 1_000L,
      tasks = submittedTasks.mapIndexed { index, task ->
        SessionQueueTaskSnapshot(
          enqueueOrder = index.toLong(),
          task = task,
          lifecycleState = QueueTaskLifecycleState.QUEUED,
        )
      },
    )

    override fun hasPendingWork(): Boolean = hasPendingWork

    override fun hasLiveManagedProcesses(): Boolean = hasLiveManagedProcesses

    override fun listSubAgentHandles(): List<SubAgentHandleState> = subAgentHandles

    override fun listDetachedControlTasks(): List<AgentTask> = detachedControlTasks.toList()

    override fun submitDetachedSubAgentRecoveryTask(
      agentId: String,
      parentRunId: String,
      taskId: String,
      createdAtEpochMs: Long,
      submissionSource: String,
    ): AgentRunSubmission = submitDetachedControlTask(
      detachedSubAgentRecoveryWaitTask(
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

    override fun ensureRecoverableDetachedSubAgentTasks(): Int {
      val activeParentRunIds = runs
        .filter(AgentRunSnapshot::isActive)
        .map(AgentRunSnapshot::runId)
        .toSet()
      val pendingRecoveryKeys = detachedControlTasks.mapNotNull { task ->
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
        handle.snapshot.state == SubAgentExecutionState.BACKGROUND_QUEUED &&
          handle.pendingApprovalResume == null &&
          handle.parentRunId !in activeParentRunIds &&
          (handle.parentRunId to handle.agentId) !in pendingRecoveryKeys
      }
      resumableHandles.forEach { handle ->
        val taskId = detachedSubAgentRecoveryTaskId(
          sessionId = sessionId,
          agentId = handle.agentId,
          parentRunId = handle.parentRunId,
        )
        submitDetachedSubAgentRecoveryTask(
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

  private fun waitingApprovalSubAgentHandle(agentId: String): SubAgentHandleState = queuedSubAgentHandle(
    agentId = agentId,
  ).copy(
    snapshot = SubAgentExecutionSnapshot(
      state = SubAgentExecutionState.WAITING_APPROVAL,
      continuationKind = SubAgentContinuationKind.PROMPT_RESUME,
      resumable = true,
      requiresUserAction = true,
      isHighRisk = false,
      headline = "Delegated child run requires approval.",
    ),
    pendingApprovalResume = SubAgentApprovalResume(
      approvedToolName = "Edit",
      promptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
      agentId = agentId,
      childRunId = "child-run-$agentId",
      childTaskId = "child-task-$agentId",
    ),
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

    override fun saveShellDestination(
      selectedTab: String,
      settingsSubpage: String?,
    ) = Unit
  }

  private class RecordingRuntimeServiceClient(
    var currentShellGateway: OpenCrayShellGateway?,
    var currentChatGateway: OpenCrayChatRuntimeGateway?,
    var dispatchChatWriteCommandHandler: ((OpenCrayChatWriteCommand) -> OpenCrayChatWriteDispatchResult)? = null,
    var currentSkillsGateway: OpenCraySkillsGateway?,
    var currentSettingsGateway: OpenCraySettingsGateway?,
    var dispatchSkillsWriteCommandHandler: ((OpenCraySkillsWriteCommand) -> OpenCraySkillsWriteDispatchResult)? = null,
    var dispatchSettingsWriteCommandHandler: ((OpenCraySettingsWriteCommand) -> OpenCraySettingsWriteDispatchResult)? = null,
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

    override fun dispatchChatWriteCommand(
      command: OpenCrayChatWriteCommand,
    ): OpenCrayChatWriteDispatchResult? = dispatchChatWriteCommandHandler?.invoke(command)

    override fun loadSkillsGateway(): OpenCraySkillsGateway? = currentSkillsGateway

    override fun peekSkillsGateway(): OpenCraySkillsGateway? = currentSkillsGateway

    override fun dispatchSkillsWriteCommand(
      command: OpenCraySkillsWriteCommand,
    ): OpenCraySkillsWriteDispatchResult? = dispatchSkillsWriteCommandHandler?.invoke(command)

    override fun loadSettingsGateway(): OpenCraySettingsGateway? = currentSettingsGateway

    override fun peekSettingsGateway(): OpenCraySettingsGateway? = currentSettingsGateway

    override fun dispatchSettingsWriteCommand(
      command: OpenCraySettingsWriteCommand,
    ): OpenCraySettingsWriteDispatchResult? = dispatchSettingsWriteCommandHandler?.invoke(command)

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

  private class ChatGatewayAvailableOnObserveClient(
    private val binderGateway: OpenCrayChatRuntimeGateway,
  ) : OpenCrayRuntimeServiceClient {
    private var observing: Boolean = false

    override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot = error("unused in test")

    override fun loadConnectionState(): RuntimeServiceConnectionState =
      if (observing) {
        RuntimeServiceConnectionState.binderConnected()
      } else {
        RuntimeServiceConnectionState.bindingPending()
      }

    override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway? =
      if (observing) binderGateway else null

    override fun peekChatRuntimeGateway(): OpenCrayChatRuntimeGateway? =
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
  ) : OpenCrayRuntimeServiceChatGateway {
    var chatPayload: Map<String, Any?> = mapOf("source" to "$label-chat")
    var chatRuntimePayload: Map<String, Any?> = mapOf("source" to "$label-runtime")
    var liveAssistantDraftEventPayload: Map<String, Any?>? = null
    var submittedText: String? = null
      private set
    var createChatSessionCallCount: Int = 0
      private set
    val copiedSessionIds = mutableListOf<String>()
    val branchedSessionRequests = mutableListOf<Pair<String, String>>()
    var memoryDebugActionRecordId: String? = null
      private set
    var memoryDebugActionId: String? = null
      private set
    var approvedTaskIdOrRunId: String? = null
      private set
    var approvedForSessionTaskIdOrRunId: String? = null
      private set
    var notifiedChatSnapshotCount: Int = 0
      private set
    private val chatListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
    private val liveDraftListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
    private val runtimeListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()

    override fun loadChatSnapshot(): Map<String, Any?> = chatPayload

    override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      chatListeners += listener
      listener(loadChatSnapshot())
      return {
        chatListeners.remove(listener)
      }
    }

    override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      liveDraftListeners += listener
      liveAssistantDraftEventPayload?.let(listener)
      return {
        liveDraftListeners.remove(listener)
      }
    }

    override fun loadChatRuntimeSnapshot(): Map<String, Any?> = chatRuntimePayload

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
      runtimeListeners += listener
      listener(loadChatRuntimeSnapshot())
      return {
        runtimeListeners.remove(listener)
      }
    }

    fun emitChatSnapshot() {
      val payload = loadChatSnapshot()
      chatListeners.toList().forEach { listener ->
        listener(payload)
      }
    }

    fun emitChatRuntimeSnapshot() {
      val payload = loadChatRuntimeSnapshot()
      runtimeListeners.toList().forEach { listener ->
        listener(payload)
      }
    }

    fun emitLiveAssistantDraftEvent() {
      val payload = liveAssistantDraftEventPayload ?: return
      liveDraftListeners.toList().forEach { listener ->
        listener(payload)
      }
    }

    override fun refreshSandboxSessionInfo() = Unit

    override fun loadMemoryDebugSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadSoulDebugSnapshot(): Map<String, Any?> = emptyMap()

    override fun searchMemoryDebug(query: String, maxResults: Int, minScore: Int): Map<String, Any?> =
      emptyMap()

    override fun getMemoryDebugSlice(path: String, fromLine: Int?, lines: Int): Map<String, Any?> =
      emptyMap()

    override fun applyMemoryDebugAction(recordId: String, actionId: String): Map<String, Any?> {
      memoryDebugActionRecordId = recordId
      memoryDebugActionId = actionId
      return mapOf(
        "source" to "$label-memory-action",
        "recordId" to recordId,
        "action" to actionId,
      )
    }

    override fun createChatSession() {
      createChatSessionCallCount += 1
    }

    override fun copyChatSession(sessionId: String) {
      copiedSessionIds += sessionId
    }

    override fun deleteChatSession(sessionId: String) = Unit

    override fun selectChatSession(sessionId: String) = Unit

    override fun branchChatSessionFromMessage(sessionId: String, messageId: String) {
      branchedSessionRequests += sessionId to messageId
    }

    override fun deleteChatMessage(sessionId: String, messageId: String) = Unit

    override fun recallChatMessage(sessionId: String, messageId: String) = Unit

    override fun submitChatMessage(
      text: String,
      attachments: List<com.opencray.runtime.OpenCrayFinalAttachment>,
    ): Map<String, Any?>? {
      submittedText = text
      return mapOf("source" to "$label-submit", "submittedText" to text)
    }

    override fun approveChatApproval(taskIdOrRunId: String) {
      approvedTaskIdOrRunId = taskIdOrRunId
    }

    override fun approveChatApprovalForSession(taskIdOrRunId: String) {
      approvedForSessionTaskIdOrRunId = taskIdOrRunId
    }

    override fun rejectChatApproval(taskIdOrRunId: String) = Unit

    override fun interruptChatRun(taskIdOrRunId: String) = Unit

    override fun retryChatRun(taskIdOrRunId: String) = Unit

    override fun notifyChatSnapshotsChanged() {
      notifiedChatSnapshotCount += 1
    }
  }

  private class RecordingSkillsGateway(
    private val label: String,
  ) : OpenCraySkillsGateway {
    var lastInstalledSourceRef: String? = null
      private set
    var observeSkillsCount: Int = 0
      private set
    var lastSetSkillEnabledSkillId: String? = null
      private set
    var lastDeletedSkillId: String? = null
      private set
    var refreshCount: Int = 0
      private set
    var lastActivatedSourceId: String? = null
      private set

    override fun loadSkillsSnapshot(query: String, suggestedLimit: Int): Map<String, Any?> = mapOf(
      "source" to "$label-skills",
      "query" to query,
      "suggestedLimit" to suggestedLimit,
    )

    override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      observeSkillsCount += 1
      listener(loadSkillsSnapshot(query = "", suggestedLimit = 0))
      return { }
    }

    override fun setSkillEnabled(skillId: String, enabled: Boolean) {
      lastSetSkillEnabledSkillId = skillId
    }

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

    override fun deleteInstalledSkill(skillId: String): String {
      lastDeletedSkillId = skillId
      return "Removed $skillId via $label."
    }

    override fun refreshSkills(): String {
      refreshCount += 1
      return "Refreshed via $label."
    }

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

    override fun activateSkillsInstallSource(sourceId: String): String {
      lastActivatedSourceId = sourceId
      return sourceId
    }
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

  private class RecordingServiceOwnedSkillsFacade : com.opencray.app.facade.skills.SkillsFacade {
    val loadQueries = mutableListOf<String>()
    val loadSuggestedLimits = mutableListOf<Int>()
    var lastLoadQuery: String? = null
      private set
    var lastLoadSuggestedLimit: Int? = null
      private set
    var lastSetSkillEnabledSkillId: String? = null
      private set
    var lastSetSkillEnabledValue: Boolean? = null
      private set
    var lastInstalledSourceRef: String? = null
      private set
    var lastInstalledSelectedSkillName: String? = null
      private set
    var lastBatchInstalledSourceRef: String? = null
      private set
    var lastBatchInstalledSkillNames: List<String> = emptyList()
      private set
    var lastCheckedSkillId: String? = null
      private set
    var lastUpdatedSkillId: String? = null
      private set

    override fun loadSnapshot(
      query: String,
      suggestedLimit: Int,
    ): com.opencray.app.facade.skills.SkillsSnapshot {
      loadQueries += query
      loadSuggestedLimits += suggestedLimit
      lastLoadQuery = query
      lastLoadSuggestedLimit = suggestedLimit
      return com.opencray.app.facade.skills.SkillsSnapshot(
        installedSkills = listOf(
          com.opencray.app.facade.skills.InstalledSkillSnapshot(
            id = "voice-notes",
            name = "voice-notes",
            description = "Voice notes",
            isEnabled = true,
            sourceDirectoryPath = "/skills/voice-notes",
            canDelete = true,
          ),
        ),
        installSources = listOf(
          com.opencray.app.facade.skills.InstallSourceSnapshot(
            id = "github-url",
            title = "GitHub",
            subtitle = "Remote source",
            actionLabel = "Inspect",
            isAvailable = true,
          ),
        ),
        suggestedSkills = listOf(
          com.opencray.app.facade.skills.SuggestedSkillSnapshot(
            id = "voice-notes",
            name = "voice-notes",
            description = "Voice notes",
            sourceRef = "github:opencray/skills",
            sourceLabel = "Remote",
          ),
        ),
      )
    }

    override fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean {
      lastSetSkillEnabledSkillId = skillId
      lastSetSkillEnabledValue = enabled
      return skillId == "voice-notes"
    }

    override fun installSkillSource(
      sourceRef: String,
      selectedSkillName: String,
    ): com.opencray.app.facade.skills.SkillInstallRequestResult {
      lastInstalledSourceRef = sourceRef
      lastInstalledSelectedSkillName = selectedSkillName
      return com.opencray.app.facade.skills.SkillInstallRequestResult(
        installedSkillId = selectedSkillName.ifBlank { "voice-notes" },
      )
    }

    override fun installSuggestedSkill(skillId: String): Boolean = false

    override fun installSkillSourceBatch(
      sourceRef: String,
      selectedSkillNames: List<String>,
    ): com.opencray.runtime.skills.SkillPackageBatchInstallAttempt {
      lastBatchInstalledSourceRef = sourceRef
      lastBatchInstalledSkillNames = selectedSkillNames
      return com.opencray.runtime.skills.SkillPackageBatchInstallAttempt(
        result = com.opencray.runtime.skills.SkillPackageBatchInstallResult(
          sourceType = "github",
          sourceRef = sourceRef,
          entries = selectedSkillNames.map { skillName ->
            com.opencray.runtime.skills.SkillPackageBatchInstallEntry(
              requestedSkillName = skillName,
              installedSkillId = skillName,
            )
          },
        ),
      )
    }

    override fun inspectSkillSource(
      sourceRef: String,
    ): com.opencray.runtime.skills.SkillSourceInspectionAttempt =
      com.opencray.runtime.skills.SkillSourceInspectionAttempt(
        result = com.opencray.runtime.skills.SkillSourceInspectionResult(
          sourceType = "github",
          sourceRef = sourceRef,
          sourcePath = "/cache/skills",
          resolvedRevision = "main",
          resolvedCommitSha = "abc123",
          candidates = listOf(
            com.opencray.runtime.skills.SkillSourceInspectionCandidate(
              name = "voice-notes",
              description = "Voice notes",
              relativePath = "voice-notes",
            ),
          ),
        ),
      )

    override fun deleteInstalledSkill(skillId: String): Boolean = skillId == "voice-notes"

    override fun refresh() = Unit

    override fun checkInstalledSkillUpdates(
      skillId: String,
    ): com.opencray.runtime.skills.SkillPackageCheckReport {
      lastCheckedSkillId = skillId
      return com.opencray.runtime.skills.SkillPackageCheckReport(
        results = listOf(
          com.opencray.runtime.skills.SkillPackageCheckResult(
            skillId = skillId,
            sourceType = "github",
            sourceRef = "github:opencray/skills",
            status = com.opencray.runtime.skills.SkillPackageCheckStatus.UPDATE_AVAILABLE,
            checkedAtEpochMs = 1_000L,
          ),
        ),
      )
    }

    override fun updateInstalledSkill(
      skillId: String,
    ): com.opencray.runtime.skills.SkillPackageUpdateReport {
      lastUpdatedSkillId = skillId
      return com.opencray.runtime.skills.SkillPackageUpdateReport(
        results = listOf(
          com.opencray.runtime.skills.SkillPackageUpdateResult(
            skillId = skillId,
            sourceType = "github",
            sourceRef = "github:opencray/skills",
            status = com.opencray.runtime.skills.SkillPackageUpdateStatus.UPDATED,
          ),
        ),
      )
    }

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
        id = selectedSkillName,
        name = selectedSkillName,
        description = "Suggested instructions",
        body = "# $selectedSkillName",
        sourceDirectoryPath = sourceRef,
        isEnabled = false,
        canDelete = false,
      )

    override fun enabledSkillRoots(): List<java.io.File> = emptyList()

    override fun activateInstallSource(sourceId: String): String = "activated:$sourceId"
  }

  private class RecordingSettingsGateway(
    private val label: String,
  ) : OpenCraySettingsGateway {
    var lastMcpMasterEnabled: Boolean? = null
      private set
    var lastNetworkSearchSlots: List<Map<String, Any?>>? = null
      private set
    var lastNotificationSettingsPayload: Map<String, Any?>? = null
      private set
    var lastMediaSpeechPayload: Map<String, Any?>? = null
      private set
    var lastPersonalizationPresetId: String? = null
      private set
    var lastPersonalizationCustomLabel: String? = null
      private set
    var lastPersonalizationCustomGuidance: String? = null
      private set
    var lastPersonalizationResetScopeId: String? = null
      private set
    var lastSafetyAutomationModeId: String? = null
      private set
    var lastSandboxSettingsPayload: Map<String, Any?>? = null
      private set
    var lastAppLanguageId: String? = null
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

    override fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?> {
      lastNetworkSearchSlots = slots
      return mapOf("source" to "$label-network-search-save", "slotCount" to slots.size)
    }

    override fun loadMediaSpeechConfig(): Map<String, Any?> =
      mapOf("source" to "$label-media-speech")

    override fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?> {
      lastMediaSpeechPayload = payload
      return mapOf("source" to "$label-media-speech-save", "keys" to payload.keys.sorted())
    }

    override fun loadSandboxSettings(): Map<String, Any?> =
      mapOf("source" to "$label-sandbox-settings")

    override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> {
      lastSandboxSettingsPayload = payload
      return mapOf("source" to "$label-sandbox-settings-save")
    }

    override fun loadLlmConfig(): Map<String, Any?> = mapOf("source" to "$label-llm")

    override fun saveLlmConfig(
      enabled: Boolean,
      streamingEnabled: Boolean?,
      providerMode: String,
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
      openAiPromptCacheKeyStrategy: String?,
      openAiPromptCacheRetention: String?,
      anthropicPromptCachingEnabled: Boolean?,
      anthropicPromptCacheTtl: String?,
      contextBudgetPreset: String?,
      contextBudgetReservedOutputTokens: Int?,
      contextBudgetSafetyMarginTokens: Int?,
      contextBudgetEffectiveInputPercent: Double?,
      selectedOnDeviceModelId: String,
      onDeviceMaxContextWindow: Int,
      onDeviceMaxTokens: Int,
      onDeviceTopK: Int,
      onDeviceTopP: Double,
      onDeviceTemperature: Double,
      onDeviceAccelerator: String,
      onDeviceThinkingEnabled: Boolean,
      onDeviceLiteModeEnabled: Boolean,
    ): Map<String, Any?> = mapOf(
      "source" to "$label-llm-save",
      "enabled" to enabled,
      "streamingEnabled" to streamingEnabled,
      "providerMode" to providerMode,
    )

    override fun saveCustomLlmProvider(
      selectedProviderOptionId: String,
      streamingEnabled: Boolean?,
      protocol: String,
      providerName: String,
      providerNotes: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
      systemPrompt: String,
      openAiPromptCacheKeyStrategy: String?,
      openAiPromptCacheRetention: String?,
      anthropicPromptCachingEnabled: Boolean?,
      anthropicPromptCacheTtl: String?,
      contextBudgetPreset: String?,
      contextBudgetReservedOutputTokens: Int?,
      contextBudgetSafetyMarginTokens: Int?,
      contextBudgetEffectiveInputPercent: Double?,
    ): Map<String, Any?> = mapOf("source" to "$label-custom-llm")

    override fun validateLlmConfig(
      providerId: String,
      protocol: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
    ): Map<String, Any?> = mapOf("source" to "$label-llm-validate", "providerId" to providerId)

    override fun downloadOnDeviceLlmModel(modelId: String): Map<String, Any?> =
      mapOf("source" to "$label-on-device-download", "modelId" to modelId)

    override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> =
      mapOf("source" to "$label-on-device-cancel", "modelId" to modelId)

    override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> =
      mapOf("source" to "$label-on-device-delete", "modelId" to modelId)

    override fun loadPersonalizationConfig(): Map<String, Any?> =
      mapOf("source" to "$label-personalization")

    override fun savePersonalizationConfig(
      presetId: String,
      customLabel: String,
      customGuidance: String,
    ): Map<String, Any?> {
      lastPersonalizationPresetId = presetId
      lastPersonalizationCustomLabel = customLabel
      lastPersonalizationCustomGuidance = customGuidance
      return mapOf("source" to "$label-personalization-save", "presetId" to presetId)
    }

    override fun setAppLanguage(languageId: String): Map<String, Any?> {
      lastAppLanguageId = languageId
      return mapOf("source" to "$label-language", "languageId" to languageId)
    }

    override fun runPersonalizationReset(scopeId: String): Map<String, Any?> {
      lastPersonalizationResetScopeId = scopeId
      return mapOf("source" to "$label-personalization-reset", "scopeId" to scopeId)
    }

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
      subAgentContextDefaultModeId: String?,
      subAgentContextProfileOverrides: Map<String, String>,
    ): Map<String, Any?> {
      lastSafetyAutomationModeId = automationModeId
      return mapOf(
        "source" to "$label-safety-save",
        "automationModeId" to automationModeId,
        "liveContextModeId" to liveContextModeId,
      )
    }
  }

  private class RecordingServiceOwnedSettingsFacade :
    com.opencray.app.facade.settings.SettingsFacade {
    var lastLoadedDetailRouteId: com.opencray.app.facade.settings.SettingsRouteId? = null
      private set

    override fun loadOverview(): com.opencray.app.facade.settings.SettingsOverviewSnapshot =
      com.opencray.app.facade.settings.SettingsOverviewSnapshot(
        eyebrow = "APP",
        title = "Settings",
        subtitle = "Configure the app.",
        deviceTitle = "Device",
        deviceSummary = "API routes",
        entries = listOf(
          com.opencray.app.facade.settings.SettingsHomeEntrySnapshot(
            routeId = com.opencray.app.facade.settings.SettingsRouteId.PERSONALIZATION,
            title = "Personalization",
          ),
        ),
      )

    override fun loadDetail(
      routeId: com.opencray.app.facade.settings.SettingsRouteId,
    ): com.opencray.app.facade.settings.SettingsDetailSnapshot {
      lastLoadedDetailRouteId = routeId
      return com.opencray.app.facade.settings.SettingsDetailSnapshot(
        routeId = routeId,
        title = routeId.wireValue,
        subtitle = "Detail for ${routeId.wireValue}",
        sections = listOf(
          com.opencray.app.facade.settings.SettingsSectionSnapshot(
            title = "Section",
            helperText = "Helper",
            rows = listOf(
              com.opencray.app.facade.settings.SettingsRowSnapshot.chevron(
                title = "Row",
                subtitle = "Subtitle",
              ),
            ),
          ),
        ),
      )
    }
  }

  private class RecordingLlmConfigFacade : com.opencray.app.facade.llm.LlmConfigFacade {
    var lastSavedRequest: com.opencray.app.facade.llm.SaveLlmConfigRequest? = null
      private set
    var lastCustomProviderRequest: com.opencray.app.facade.llm.SaveCustomLlmProviderRequest? = null
      private set
    var lastValidatedRequest: com.opencray.app.facade.llm.ValidateLlmConfigRequest? = null
      private set

    override fun load(): com.opencray.app.facade.llm.LlmConfigSnapshot = snapshot(
      selectedProviderOptionId = "custom-provider",
      protocol = "anthropic",
      providerName = "Custom",
      providerNotes = "Notes",
      baseUrl = "https://example.com",
      apiKey = "secret",
      model = "kimi-k2.5",
      reasoningEffort = "medium",
      systemPrompt = "Prompt",
    )

    override fun save(
      request: com.opencray.app.facade.llm.SaveLlmConfigRequest,
    ): com.opencray.app.facade.llm.LlmConfigSnapshot {
      lastSavedRequest = request
      return snapshot(
        enabled = request.enabled,
        selectedProviderOptionId = request.selectedProviderOptionId,
        protocol = request.protocol,
        providerName = request.providerName,
        providerNotes = request.providerNotes,
        baseUrl = request.baseUrl,
        apiKey = request.apiKey,
        model = request.model,
        reasoningEffort = request.reasoningEffort,
        systemPrompt = request.systemPrompt,
        contextBudgetPreset = request.contextBudgetPreset ?: LlmSettingsState.DEFAULT_CONTEXT_BUDGET_PRESET,
        contextBudgetReservedOutputTokens = request.contextBudgetReservedOutputTokens,
        contextBudgetSafetyMarginTokens = request.contextBudgetSafetyMarginTokens,
        contextBudgetEffectiveInputPercent = request.contextBudgetEffectiveInputPercent,
      )
    }

    override fun saveCustomProvider(
      request: com.opencray.app.facade.llm.SaveCustomLlmProviderRequest,
    ): com.opencray.app.facade.llm.LlmConfigSnapshot {
      lastCustomProviderRequest = request
      return snapshot(
        enabled = true,
        selectedProviderOptionId = request.selectedProviderOptionId,
        protocol = request.protocol,
        providerName = request.providerName,
        providerNotes = request.providerNotes,
        baseUrl = request.baseUrl,
        apiKey = request.apiKey,
        model = request.model,
        reasoningEffort = request.reasoningEffort,
        systemPrompt = request.systemPrompt,
        contextBudgetPreset = request.contextBudgetPreset ?: LlmSettingsState.DEFAULT_CONTEXT_BUDGET_PRESET,
        contextBudgetReservedOutputTokens = request.contextBudgetReservedOutputTokens,
        contextBudgetSafetyMarginTokens = request.contextBudgetSafetyMarginTokens,
        contextBudgetEffectiveInputPercent = request.contextBudgetEffectiveInputPercent,
      )
    }

    override fun validate(
      request: com.opencray.app.facade.llm.ValidateLlmConfigRequest,
    ): com.opencray.app.facade.llm.LlmValidationResult {
      lastValidatedRequest = request
      return com.opencray.app.facade.llm.LlmValidationResult(
        isSuccess = true,
        message = "validated",
        agentCapability = LlmAgentCapabilitySnapshot(
          routeFingerprint = llmRouteFingerprint(
            protocol = request.protocol,
            baseUrl = request.baseUrl,
            model = request.model,
          ),
          verifiedAtEpochMs = 123L,
          visionInputSupported = true,
          nativeToolCallingAvailable = true,
          toolChoiceSupported = true,
          parallelToolCallsSupported = true,
          strictToolSchemaSupported = true,
        ),
      )
    }

    override fun downloadOnDeviceModel(
      modelId: String,
    ): com.opencray.app.facade.llm.LlmConfigSnapshot = load()

    override fun cancelOnDeviceModelDownload(
      modelId: String,
    ): com.opencray.app.facade.llm.LlmConfigSnapshot = load()

    override fun deleteOnDeviceModel(
      modelId: String,
    ): com.opencray.app.facade.llm.LlmConfigSnapshot = load()

    private fun snapshot(
      enabled: Boolean = true,
      selectedProviderOptionId: String,
      protocol: String,
      providerName: String,
      providerNotes: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
      systemPrompt: String,
      contextBudgetPreset: String = LlmSettingsState.DEFAULT_CONTEXT_BUDGET_PRESET,
      contextBudgetReservedOutputTokens: Int? = null,
      contextBudgetSafetyMarginTokens: Int? = null,
      contextBudgetEffectiveInputPercent: Double? = null,
    ): com.opencray.app.facade.llm.LlmConfigSnapshot =
      com.opencray.app.facade.llm.LlmConfigSnapshot(
        localeTag = "en",
        enabled = enabled,
        providerId = "custom",
        selectedProviderOptionId = selectedProviderOptionId,
        protocol = protocol,
        providerOptions = listOf(
          com.opencray.app.facade.llm.LlmProviderOptionSnapshot(
            id = "custom-provider",
            providerId = "custom",
            title = "Custom",
            subtitle = "Notes",
            defaultBaseUrl = "https://example.com",
            defaultModel = "kimi-k2.5",
            protocol = "anthropic",
            apiKey = "",
            isCustom = true,
          ),
          com.opencray.app.facade.llm.LlmProviderOptionSnapshot(
            id = "custom-provider-2",
            providerId = "custom",
            title = "Custom 2",
            subtitle = "More notes",
            defaultBaseUrl = "https://provider.example.com",
            defaultModel = "claude-kimi-hybrid",
            protocol = "anthropic",
            apiKey = "",
            isCustom = true,
          ),
        ),
        providerName = providerName,
        providerNotes = providerNotes,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        reasoningEffort = reasoningEffort,
        systemPrompt = systemPrompt,
        contextBudgetPreset = contextBudgetPreset,
        contextBudgetReservedOutputTokens = contextBudgetReservedOutputTokens,
        contextBudgetSafetyMarginTokens = contextBudgetSafetyMarginTokens,
        contextBudgetEffectiveInputPercent = contextBudgetEffectiveInputPercent,
        helperText = "helper",
        agentCapability = LlmAgentCapabilitySnapshot(
          routeFingerprint = llmRouteFingerprint(
            protocol = protocol,
            baseUrl = baseUrl,
            model = model,
          ),
          verifiedAtEpochMs = 100L,
          nativeToolCallingAvailable = true,
        ),
      )
  }

  private class RecordingSandboxSettingsGatewayAccess : SandboxSettingsGatewayAccess {
    var lastSavedState: SandboxSettingsState? = null
      private set
    var lastSavedApiKey: String? = null
      private set

    private var current: ResolvedSandboxSettings = ResolvedSandboxSettings(
      state = SandboxSettingsState(
        enabled = false,
        providerId = "local",
        defaultBackend = "local",
        sessionMode = "persistent",
        autoResume = false,
        idleTimeoutMinutes = 15,
        startupTimeoutMs = 60_000L,
        requestTimeoutMs = 120_000L,
        timeoutAction = "stop",
        templateId = "default",
      ),
      e2bApiKey = null,
    )

    override fun load(): ResolvedSandboxSettings = current

    override fun save(
      state: SandboxSettingsState,
      e2bApiKey: String?,
    ): ResolvedSandboxSettings {
      lastSavedState = state
      lastSavedApiKey = e2bApiKey
      current = ResolvedSandboxSettings(
        state = state,
        e2bApiKey = e2bApiKey?.takeIf(String::isNotBlank),
      )
      return current
    }
  }

  private class RecordingStrongBackgroundSettingsAccess : StrongBackgroundSettingsAccess {
    var lastActionId: String? = null
      private set

    override fun loadSnapshot(): Map<String, Any?> = mapOf(
      "source" to "service-strong-background",
      "available" to true,
      "tierId" to StrongBackgroundTierIds.ACTIVE_BACKGROUND,
      "setupComplete" to false,
      "recommendedActionIds" to listOf(StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS),
      "actions" to listOf(
        mapOf(
          "id" to StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
          "available" to true,
          "recommended" to true,
        ),
      ),
    )

    override fun performAction(actionId: String): Map<String, Any?> {
      lastActionId = actionId
      return mapOf(
        "source" to "service-strong-background-action",
        "actionId" to actionId,
        "available" to true,
        "launched" to true,
      )
    }
  }

  private class RecordingAppLanguageSettingsGatewayAccess : AppLanguageSettingsGatewayAccess {
    var lastLanguageId: String? = null
      private set

    override fun setAppLanguage(languageId: String): Map<String, Any?> {
      lastLanguageId = languageId
      return mapOf(
        "source" to "service-language",
        "languageId" to languageId,
      )
    }
  }

  private class RecordingNetworkSearchConfigFacade :
    com.opencray.app.facade.search.NetworkSearchConfigFacade {
    var lastSavedRequest: com.opencray.app.facade.search.SaveNetworkSearchConfigRequest? = null
      private set

    override fun load(): com.opencray.app.facade.search.NetworkSearchConfigSnapshot = snapshot(
      slots = listOf(
        com.opencray.app.facade.search.NetworkSearchSlotSnapshot(
          id = "default",
          providerId = "exa",
          label = "Exa",
          baseUrl = "https://api.exa.ai",
          model = "exa-search",
          apiKey = "search-key",
          enabled = true,
        ),
      ),
    )

    override fun save(
      request: com.opencray.app.facade.search.SaveNetworkSearchConfigRequest,
    ): com.opencray.app.facade.search.NetworkSearchConfigSnapshot {
      lastSavedRequest = request
      return snapshot(
        slots = request.slots.map { slot ->
          com.opencray.app.facade.search.NetworkSearchSlotSnapshot(
            id = slot.id,
            providerId = slot.providerId,
            label = slot.label,
            baseUrl = slot.baseUrl,
            model = slot.model,
            apiKey = slot.apiKey,
            enabled = slot.enabled,
          )
        },
      )
    }

    private fun snapshot(
      slots: List<com.opencray.app.facade.search.NetworkSearchSlotSnapshot>,
    ): com.opencray.app.facade.search.NetworkSearchConfigSnapshot =
      com.opencray.app.facade.search.NetworkSearchConfigSnapshot(
        localeTag = "en",
        title = "Network & Search",
        subtitle = "Add API keys here. Enabled slots run top to bottom.",
        slots = slots,
      )
  }

  private class RecordingMediaSpeechSettingsFacade :
    com.opencray.app.facade.media.MediaSpeechSettingsFacade {
    var lastSavedRequest: com.opencray.app.facade.media.SaveMediaSpeechConfigRequest? = null
      private set

    override fun load(): com.opencray.app.facade.media.MediaSpeechConfigSnapshot = snapshot(
      imageGeneration = com.opencray.app.facade.media.MediaProviderSnapshot(
        provider = "openai",
        baseUrl = "https://image.example.com",
        endpoint = "/images",
        model = "gpt-image-1",
        authProtocol = ProviderAuthProtocols.BEARER,
        apiKey = "image-key",
      ),
      videoGeneration = com.opencray.app.facade.media.MediaProviderSnapshot(
        provider = "runway",
        baseUrl = "https://video.example.com",
        endpoint = "/videos",
        model = "gen4",
        authProtocol = ProviderAuthProtocols.BEARER,
        apiKey = "video-key",
      ),
      voiceGeneration = com.opencray.app.facade.media.VoiceProviderSnapshot(
        provider = "openai",
        baseUrl = "https://voice.example.com",
        endpoint = "/speech",
        model = "tts-1",
        voicePreset = "alloy",
        authProtocol = ProviderAuthProtocols.BEARER,
        apiKey = "voice-key",
      ),
      sttRouteId = "on_device",
      externalStt = com.opencray.app.facade.media.MediaProviderSnapshot(
        provider = "deepgram",
        baseUrl = "https://stt.example.com",
        endpoint = "/listen",
        model = "nova-3",
        authProtocol = ProviderAuthProtocols.BEARER,
        apiKey = "stt-key",
      ),
      onDeviceModel = com.opencray.app.facade.media.OnDeviceSttSnapshot(
        modelPackage = "tiny.en",
        downloadStatus = "not_downloaded",
      ),
    )

    override fun save(
      request: com.opencray.app.facade.media.SaveMediaSpeechConfigRequest,
    ): com.opencray.app.facade.media.MediaSpeechConfigSnapshot {
      lastSavedRequest = request
      return snapshot(
        imageGeneration = com.opencray.app.facade.media.MediaProviderSnapshot(
          provider = request.imageGeneration.provider,
          baseUrl = request.imageGeneration.baseUrl,
          endpoint = request.imageGeneration.endpoint,
          model = request.imageGeneration.model,
          authProtocol = request.imageGeneration.authProtocol,
          apiKey = request.imageGeneration.apiKey,
        ),
        videoGeneration = com.opencray.app.facade.media.MediaProviderSnapshot(
          provider = request.videoGeneration.provider,
          baseUrl = request.videoGeneration.baseUrl,
          endpoint = request.videoGeneration.endpoint,
          model = request.videoGeneration.model,
          authProtocol = request.videoGeneration.authProtocol,
          apiKey = request.videoGeneration.apiKey,
        ),
        voiceGeneration = com.opencray.app.facade.media.VoiceProviderSnapshot(
          provider = request.voiceGeneration.provider,
          baseUrl = request.voiceGeneration.baseUrl,
          endpoint = request.voiceGeneration.endpoint,
          model = request.voiceGeneration.model,
          voicePreset = request.voiceGeneration.voicePreset,
          authProtocol = request.voiceGeneration.authProtocol,
          apiKey = request.voiceGeneration.apiKey,
        ),
        sttRouteId = request.sttRouteId,
        externalStt = com.opencray.app.facade.media.MediaProviderSnapshot(
          provider = request.externalStt.provider,
          baseUrl = request.externalStt.baseUrl,
          endpoint = request.externalStt.endpoint,
          model = request.externalStt.model,
          authProtocol = request.externalStt.authProtocol,
          apiKey = request.externalStt.apiKey,
        ),
        onDeviceModel = com.opencray.app.facade.media.OnDeviceSttSnapshot(
          modelPackage = request.onDeviceModel.modelPackage,
          downloadStatus = request.onDeviceModel.downloadStatus,
        ),
      )
    }

    private fun snapshot(
      imageGeneration: com.opencray.app.facade.media.MediaProviderSnapshot,
      videoGeneration: com.opencray.app.facade.media.MediaProviderSnapshot,
      voiceGeneration: com.opencray.app.facade.media.VoiceProviderSnapshot,
      sttRouteId: String,
      externalStt: com.opencray.app.facade.media.MediaProviderSnapshot,
      onDeviceModel: com.opencray.app.facade.media.OnDeviceSttSnapshot,
    ): com.opencray.app.facade.media.MediaSpeechConfigSnapshot =
      com.opencray.app.facade.media.MediaSpeechConfigSnapshot(
        localeTag = "en",
        title = "Media & Speech",
        subtitle = "Configure media APIs and STT routing.",
        imageGeneration = imageGeneration,
        videoGeneration = videoGeneration,
        voiceGeneration = voiceGeneration,
        sttRouteId = sttRouteId,
        externalStt = externalStt,
        onDeviceModel = onDeviceModel,
      )
  }

  private class RecordingPersonalizationFacade :
    com.opencray.app.facade.personalization.PersonalizationFacade {
    var lastSavedRequest: com.opencray.app.facade.personalization.SavePersonalizationConfigRequest? = null
      private set
    var lastSetLanguageId: String? = null
      private set
    var lastResetScope: com.opencray.app.facade.personalization.PersonalizationResetScope? = null
      private set

    override fun load(): com.opencray.app.facade.personalization.PersonalizationConfigSnapshot = snapshot()

    override fun save(
      request: com.opencray.app.facade.personalization.SavePersonalizationConfigRequest,
    ): com.opencray.app.facade.personalization.PersonalizationConfigSnapshot {
      lastSavedRequest = request
      return snapshot(
        selectedPresetId = request.presetId,
        customLabel = request.customLabel,
        customGuidance = request.customGuidance,
      )
    }

    override fun setAppLanguage(
      languageId: String,
    ): com.opencray.app.facade.personalization.PersonalizationConfigSnapshot {
      lastSetLanguageId = languageId
      return snapshot(selectedAppLanguageId = languageId)
    }

    override fun reset(
      scope: com.opencray.app.facade.personalization.PersonalizationResetScope,
    ): com.opencray.app.facade.personalization.PersonalizationConfigSnapshot {
      lastResetScope = scope
      return snapshot(
        lastResetMessage = when (scope) {
          com.opencray.app.facade.personalization.PersonalizationResetScope.MEMORY ->
            "Memory reset staged."

          com.opencray.app.facade.personalization.PersonalizationResetScope.SOUL ->
            "Soul reset staged."
        },
      )
    }

    private fun snapshot(
      selectedPresetId: String = "steady",
      customLabel: String = "OpenCray",
      customGuidance: String = "",
      selectedAppLanguageId: String = "en",
      lastResetMessage: String? = null,
    ): com.opencray.app.facade.personalization.PersonalizationConfigSnapshot =
      com.opencray.app.facade.personalization.PersonalizationConfigSnapshot(
        title = "Personalization",
        subtitle = "Shape the assistant.",
        introTitle = "Intro",
        introBody = "Body",
        introHelper = "Helper",
        presetsTitle = "Presets",
        presetsHelper = "Preset helper",
        presets = listOf(
          com.opencray.app.facade.personalization.PersonalizationPresetSnapshot(
            id = "steady",
            title = "Steady",
            summary = "Balanced",
            voice = "Calm",
            status = if (selectedPresetId == "steady") "Selected" else "Available",
            isSelected = selectedPresetId == "steady",
          ),
          com.opencray.app.facade.personalization.PersonalizationPresetSnapshot(
            id = "warm",
            title = "Warm",
            summary = "Supportive",
            voice = "Warm",
            status = if (selectedPresetId == "warm") "Selected" else "Available",
            isSelected = selectedPresetId == "warm",
          ),
        ),
        selectedPresetId = selectedPresetId,
        customOverlayTitle = "Overlay",
        customOverlayHelper = "Overlay helper",
        customLabelHint = "Label",
        customLabelHelper = "Label helper",
        customGuidanceHint = "Guidance",
        customGuidanceHelper = "Guidance helper",
        customLabel = customLabel,
        customGuidance = customGuidance,
        behaviorDefaultsTitle = "Defaults",
        appLanguageTitle = "Language",
        appLanguageOptions = listOf(
          com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot(
            id = "en",
            title = "English",
            isSelected = selectedAppLanguageId == "en",
          ),
          com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot(
            id = "zh-CN",
            title = "简体中文",
            isSelected = selectedAppLanguageId == "zh-CN",
          ),
        ),
        selectedAppLanguageId = selectedAppLanguageId,
        livePreviewTitle = "Preview",
        livePreviewName = customLabel,
        livePreviewSummary = if (customGuidance.isBlank()) {
          "Default summary"
        } else {
          customGuidance
        },
        queueTitle = "Queue",
        queueBody = "Idle",
        queueIsIdle = true,
        lastResetTitle = "Last reset",
        lastResetMessage = lastResetMessage,
        resetActions = listOf(
          com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot(
            scope = com.opencray.app.facade.personalization.PersonalizationResetScope.MEMORY,
            title = "Reset memory",
            scopeBody = "Clear memory.",
            retainBody = "Keep profile.",
            confirmationToken = "RESET MEMORY",
            inputHint = "Type RESET MEMORY",
            disabledGuidance = "Queue must be idle.",
            typeExactGuidance = "Type exact token.",
            armedGuidance = "Ready.",
            isInputEnabled = true,
          ),
          com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot(
            scope = com.opencray.app.facade.personalization.PersonalizationResetScope.SOUL,
            title = "Reset soul",
            scopeBody = "Clear soul.",
            retainBody = "Keep memory.",
            confirmationToken = "RESET SOUL",
            inputHint = "Type RESET SOUL",
            disabledGuidance = "Queue must be idle.",
            typeExactGuidance = "Type exact token.",
            armedGuidance = "Ready.",
            isInputEnabled = true,
          ),
        ),
      )
  }

  private class RecordingOnDeviceWarmupAccess : OnDeviceLlmWarmupAccess {
    var ensureWarmForActiveSessionCallCount: Int = 0
      private set

    override fun ensureWarmForSession(sessionId: String): OnDeviceLlmWarmupState =
      OnDeviceLlmWarmupState()

    override fun ensureWarmForActiveSession(): OnDeviceLlmWarmupState {
      ensureWarmForActiveSessionCallCount += 1
      return OnDeviceLlmWarmupState()
    }

    override fun clear(): OnDeviceLlmWarmupState = OnDeviceLlmWarmupState()
  }

  private class RecordingSafetySettingsFacade : com.opencray.app.facade.safety.SafetySettingsFacade {
    var lastSavedRequest: com.opencray.app.facade.safety.SaveSafetySettingsRequest? = null
      private set

    override fun load(): com.opencray.app.facade.safety.SafetySettingsSnapshot = snapshot()

    override fun save(
      request: com.opencray.app.facade.safety.SaveSafetySettingsRequest,
    ): com.opencray.app.facade.safety.SafetySettingsSnapshot {
      lastSavedRequest = request
      return snapshot(
        automationModeId = request.automationModeId,
        rollbackJournalEnabled = request.rollbackJournalEnabled,
        maxFilesPerBatch = request.maxFilesPerBatch,
        maxAgentTurns = request.maxAgentTurns,
        maxToolCalls = request.maxToolCalls,
        undoWindowHours = request.undoWindowHours,
        fileChangesPolicyId = request.fileChangesPolicyId,
        fileDeletesPolicyId = request.fileDeletesPolicyId,
        shellCommandsPolicyId = request.shellCommandsPolicyId,
        externalAccessModeId = request.externalAccessModeId,
        photoLibraryEnabled = request.photoLibraryEnabled,
        downloadsEnabled = request.downloadsEnabled,
        documentsEnabled = request.documentsEnabled,
        recordingsEnabled = request.recordingsEnabled,
        workspaceAccessProfileId = request.workspaceAccessProfileId,
        readOnlyOutsideWorkspace = request.readOnlyOutsideWorkspace,
        liveContextModeId = request.liveContextModeId,
        memoryToolsEnabled = request.memoryToolsEnabled,
      )
    }

    private fun snapshot(
      automationModeId: String = "auto",
      rollbackJournalEnabled: Boolean = false,
      maxFilesPerBatch: Int = 5,
      maxAgentTurns: Int = 12,
      maxToolCalls: Int = 20,
      undoWindowHours: Int = 24,
      fileChangesPolicyId: String = "ask",
      fileDeletesPolicyId: String = "ask",
      shellCommandsPolicyId: String = "ask",
      externalAccessModeId: String = "allow",
      photoLibraryEnabled: Boolean = false,
      downloadsEnabled: Boolean = true,
      documentsEnabled: Boolean = true,
      recordingsEnabled: Boolean = false,
      workspaceAccessProfileId: String = "workspace_write",
      readOnlyOutsideWorkspace: Boolean = false,
      liveContextModeId: String = "full",
      memoryToolsEnabled: Boolean = true,
    ): com.opencray.app.facade.safety.SafetySettingsSnapshot =
      com.opencray.app.facade.safety.SafetySettingsSnapshot(
        automationMode = com.opencray.policy.SafetyAutomationMode.fromWireValue(automationModeId),
        rollbackJournalEnabled = rollbackJournalEnabled,
        maxFilesPerBatch = maxFilesPerBatch,
        maxAgentTurns = maxAgentTurns,
        maxToolCalls = maxToolCalls,
        undoWindowHours = undoWindowHours,
        fileChangesPolicy = com.opencray.policy.ToolPolicyOverride.fromWireValue(fileChangesPolicyId),
        fileDeletesPolicy = com.opencray.policy.ToolPolicyOverride.fromWireValue(fileDeletesPolicyId),
        shellCommandsPolicy = com.opencray.policy.ToolPolicyOverride.fromWireValue(shellCommandsPolicyId),
        externalAccessMode = com.opencray.policy.ExternalAccessMode.fromWireValue(externalAccessModeId),
        locations = listOf(
          com.opencray.app.facade.safety.SafetySettingsLocationSnapshot(
            id = "photo_library",
            enabled = photoLibraryEnabled,
          ),
          com.opencray.app.facade.safety.SafetySettingsLocationSnapshot(
            id = "downloads",
            enabled = downloadsEnabled,
          ),
          com.opencray.app.facade.safety.SafetySettingsLocationSnapshot(
            id = "documents",
            enabled = documentsEnabled,
          ),
          com.opencray.app.facade.safety.SafetySettingsLocationSnapshot(
            id = "recordings",
            enabled = recordingsEnabled,
          ),
        ),
        workspaceAccessProfile = com.opencray.policy.WorkspaceAccessProfile.fromWireValue(
          workspaceAccessProfileId,
        ),
        readOnlyOutsideWorkspace = readOnlyOutsideWorkspace,
        liveContextMode = LiveContextMode.fromWireValue(liveContextModeId),
        memoryToolsEnabled = memoryToolsEnabled,
      )
  }

  private class RecordingMcpSettingsFacade : com.opencray.app.facade.mcp.McpSettingsFacade {
    var lastMasterEnabledValue: Boolean? = null
      private set
    var lastServerEnabledId: String? = null
      private set
    var lastServerEnabledValue: Boolean? = null
      private set

    override fun load(): com.opencray.app.facade.mcp.McpSettingsSnapshot =
      snapshot(masterEnabled = true, serverActionEnabled = false)

    override fun setMasterEnabled(enabled: Boolean): com.opencray.app.facade.mcp.McpSettingsSnapshot {
      lastMasterEnabledValue = enabled
      return snapshot(masterEnabled = enabled, serverActionEnabled = false)
    }

    override fun setServerEnabled(
      serverId: String,
      enabled: Boolean,
    ): com.opencray.app.facade.mcp.McpSettingsSnapshot {
      lastServerEnabledId = serverId
      lastServerEnabledValue = enabled
      return snapshot(masterEnabled = true, serverActionEnabled = enabled)
    }

    override fun currentExposureReport(): com.opencray.mcp.McpClientExposureReport =
      com.opencray.mcp.McpClientExposureReport(
        activeClients = emptyList(),
        blockedClients = emptyList(),
      )

    private fun snapshot(
      masterEnabled: Boolean,
      serverActionEnabled: Boolean,
    ): com.opencray.app.facade.mcp.McpSettingsSnapshot =
      com.opencray.app.facade.mcp.McpSettingsSnapshot(
        title = "MCP",
        subtitle = "Subtitle",
        masterTitle = "Master",
        masterSummary = "Summary",
        masterEnabled = masterEnabled,
        summaryLine = "1 active",
        serversTitle = "Servers",
        serversHelper = "Helper",
        masterDisabledTitle = if (masterEnabled) null else "Disabled",
        masterDisabledBody = if (masterEnabled) null else "Disabled body",
        servers = listOf(
          com.opencray.app.facade.mcp.McpServerSettingsSnapshot(
            id = "filesystem",
            title = "Filesystem",
            statusLabel = "Active",
            statusTone = "active",
            trustLine = "Trusted",
            authLine = "Ready",
            readinessLine = "Ready",
            transportLine = "Local",
            exposureLine = "Exposed",
            guidance = "Guidance",
            actionLabel = if (serverActionEnabled) "Disable" else "Enable",
            actionTurnsOn = !serverActionEnabled,
            isActionEnabled = serverActionEnabled,
          ),
        ),
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

  private data class PendingApprovalServiceHostFixture(
    val serviceHost: OpenCrayRuntimeServiceHost,
    val sessionId: String,
    val runId: String,
    val taskId: String,
    val chatStore: ChatSessionLocalStore,
    val checkpointStore: PromptCheckpointStore,
    val journalStore: RunEventJournalStore,
    val handle: RecordingAgentSessionHandle,
    val approvalRegistry: AgentTaskApprovalRegistry,
    val subAgentReplayEvents: List<OpenCraySubAgentEvent>,
  )

  private fun pendingApprovalServiceHostFixture(
    root: java.io.File,
    resumeRequestResult: Boolean = false,
    cancelRequestResult: Boolean = false,
    appendInterruptEvent: Boolean = false,
    includeQueueApprovalTask: Boolean = true,
    subAgentHandles: List<SubAgentHandleState> = emptyList(),
    resultMetadata: Map<String, String> = mapOf(
      "toolName" to "Bash",
      "canonicalToolName" to "bash",
    ),
    checkpointToolName: String = "bash",
  ): PendingApprovalServiceHostFixture {
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runId = "pending-approval-run-1"
    val taskId = "pending-approval-task-1"
    val pendingMessageId = "pending-message-1"
    chatStore.appendMessage(sessionId, ChatTranscriptRole.USER, "Need approval")
    val queueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      lifecycleState = SessionLifecycleState.RUNNING,
      updatedAtEpochMs = 1_200L,
      tasks = if (includeQueueApprovalTask) {
        listOf(
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
                AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.SUSPENDED,
            attempt = 1,
            lastErrorCode = "APPROVAL_REQUIRED",
            lastErrorMessage = "Approval is required before Bash can run.",
          ),
        )
      } else {
        emptyList()
      },
    )
    val runSnapshot = AgentRunSnapshot(
      sessionId = sessionId,
      runId = runId,
      taskId = taskId,
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_200L,
      lifecycleState = if (includeQueueApprovalTask) QueueTaskLifecycleState.SUSPENDED else null,
      taskState = if (includeQueueApprovalTask) AgentTaskState.SUSPENDED else null,
      attempt = 1,
      executionOrdinal = 1,
      executionId = "execution-1",
      executionKind = "initial",
      executionStatus = ExecutionStatus.DENIED,
      errorCode = "APPROVAL_REQUIRED",
      errorMessage = "Approval is required before Bash can run.",
      resultMetadata = resultMetadata,
      pendingMessageId = pendingMessageId,
    )
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    val handle = RecordingAgentSessionHandle(
      sessionId = sessionId,
      resumedSessionIds = runtimeManager.resumedSessionIds,
      runs = listOf(runSnapshot),
      queueSnapshot = queueSnapshot,
      cancelRequestResult = cancelRequestResult,
      resumeRequestResult = resumeRequestResult,
      subAgentHandles = subAgentHandles,
    )
    runtimeManager.putHandle(handle)
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val checkpointStore = promptCheckpointStoreFactory.forChatSession(sessionId)
    checkpointStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        checkpointId = "checkpoint-waiting-approval",
        checkpointKind = PromptCheckpointKind.WAITING_APPROVAL,
        createdAtEpochMs = 1_200L,
        updatedAtEpochMs = 1_200L,
        toolName = checkpointToolName,
        pendingMessageId = pendingMessageId,
      ),
    )
    val journalStore = runEventJournalStoreFactory.forChatSession(sessionId)
    if (appendInterruptEvent) {
      journalStore.append(
        OpenCrayCancellationEvent(
          runId = runId,
          taskId = taskId,
          executionId = "execution-1",
          executionOrdinal = 1,
          executionKind = "initial",
          outcome = "user_interrupted",
          text = "Interrupted while waiting for approval.",
          emittedAtEpochMs = 1_300L,
        ),
      )
    }
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      private val stores = linkedMapOf<String, SessionSupplementStore>()

      override fun forChatSession(sessionId: String): SessionSupplementStore =
        stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
    }
    val approvalRegistry = AgentTaskApprovalRegistry()
    val subAgentReplayEvents = mutableListOf<OpenCraySubAgentEvent>()
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
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, event -> subAgentReplayEvents += event },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )
    val serviceHost = OpenCrayRuntimeServiceHost(
      dependencies = runtimeTestDependencies(
        root = root,
        chatStore = chatStore,
      ),
      runtimeAccess = runtimeAccess,
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(),
      serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
        workSummaryProvider = runtimeAccess.hostAccess::activeWorkSummary,
      ).apply { refresh() },
    )
    return PendingApprovalServiceHostFixture(
      serviceHost = serviceHost,
      sessionId = sessionId,
      runId = runId,
      taskId = taskId,
      chatStore = chatStore,
      checkpointStore = checkpointStore,
      journalStore = journalStore,
      handle = handle,
      approvalRegistry = approvalRegistry,
      subAgentReplayEvents = subAgentReplayEvents,
    )
  }

  private fun runtimeTestDependencies(
    root: java.io.File,
    chatStore: ChatSessionLocalStore,
  ): OpenCrayRuntimeContextDependencies {
    val workspaceRoot = root.toPath()
    return OpenCrayRuntimeContextDependencies(
      appContext = ContextWrapper(null),
      localizedContext = ContextWrapper(null),
      llmSettingsStore = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore()),
      sandboxSettingsRepository = testSandboxSettingsRepository(),
      personalizationStore = PersonalizationLocalStore(root.resolve("personalization")),
      chatSessionStore = chatStore,
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
