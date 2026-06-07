package com.opencray.app

import android.content.ContextWrapper
import com.opencray.app.facade.safety.EmptySafetySettingsFacade
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.subagent.SubAgentApprovalResume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScheduledTaskRuntimeTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedScheduledTaskStoresPersistNormalizedEntries() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-runtime-root")
    val specStore = FileBackedScheduledTaskSpecStoreFactory(runtimeRoot).create()
    val runRecordStore = FileBackedScheduledTaskRunRecordStoreFactory(runtimeRoot).create()
    val triggerSyncStateStore = FileBackedScheduledTaskTriggerSyncStateStoreFactory(runtimeRoot).create()
    val sessionId = "session-store"
    val original = scheduledTaskSpec(
      sessionId = sessionId,
      scheduleId = "schedule-store",
      title = "Original",
      updatedAtEpochMs = 1_000L,
    )
    val updated = original.copy(
      title = "Updated",
      updatedAtEpochMs = 2_000L,
    )

    specStore.upsert(original)
    specStore.upsert(updated)
    runRecordStore.upsert(
      ScheduledTaskRunRecord(
        scheduleRunId = "schedule-run-store",
        scheduleId = updated.scheduleId,
        sessionId = sessionId,
        triggerReason = ScheduledTaskTriggerReasons.ALARM,
        triggeredAtEpochMs = 3_000L,
        result = ScheduledTaskRunResult.TRIGGERED,
        updatedAtEpochMs = 3_000L,
      ),
    )
    runRecordStore.upsert(
      ScheduledTaskRunRecord(
        scheduleRunId = "schedule-run-store",
        scheduleId = updated.scheduleId,
        sessionId = sessionId,
        triggerReason = ScheduledTaskTriggerReasons.ALARM,
        triggeredAtEpochMs = 3_000L,
        acceptedAtEpochMs = 3_100L,
        createdRunId = "run-store",
        createdTaskId = "task-store",
        result = ScheduledTaskRunResult.ACCEPTED,
        updatedAtEpochMs = 3_100L,
      ),
    )
    triggerSyncStateStore.replaceScheduleIds(
      linkedSetOf(" schedule-store ", "", "schedule-store", "schedule-two"),
    )

    val reloadedSpecStore = FileBackedScheduledTaskSpecStoreFactory(runtimeRoot).create()
    val reloadedRunRecordStore = FileBackedScheduledTaskRunRecordStoreFactory(runtimeRoot).create()
    val reloadedTriggerSyncStateStore =
      FileBackedScheduledTaskTriggerSyncStateStoreFactory(runtimeRoot).create()

    assertEquals(listOf("Updated"), reloadedSpecStore.list().map(ScheduledTaskSpec::title))
    assertEquals(1, reloadedSpecStore.listEnabled().size)
    assertEquals(
      ScheduledTaskRunResult.ACCEPTED,
      reloadedRunRecordStore.get("schedule-run-store")?.result,
    )
    assertEquals(
      listOf("run-store"),
      reloadedRunRecordStore.listForSchedule(updated.scheduleId).mapNotNull(ScheduledTaskRunRecord::createdRunId),
    )
    assertEquals(
      linkedSetOf("schedule-store", "schedule-two"),
      reloadedTriggerSyncStateStore.loadScheduleIds(),
    )
  }

  @Test
  fun scheduledTaskDispatcherQueuesPromptRunAndWritesTranscriptPlaceholder() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-dispatch-root")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val runRecordStore = InMemoryScheduledTaskRunRecordStoreFactory().create()
    val session = RecordingScheduledTaskSessionAccess(sessionId = sessionId)
    val registrar = RecordingScheduledTriggerRegistrar()
    val spec = scheduledTaskSpec(sessionId = sessionId)
    specStore.upsert(spec)
    val baselineMessageCount = checkNotNull(chatStore.loadSession(sessionId)).messages.size
    val dispatcher = ScheduledTaskDispatcher(
      hostAccess = RecordingScheduledRuntimeHostAccess(session),
      chatSessionStore = chatStore,
      safetySettingsFacade = EmptySafetySettingsFacade,
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = emptySet(),
          summary = "workspace=/workspace",
        )
      },
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      localizedContext = ContextWrapper(null),
      assistantPlaceholderTextProvider = { "Thinking…" },
      specStore = specStore,
      runRecordStore = runRecordStore,
      triggerRegistrar = registrar,
      clock = { 5_000L },
    )

    val outcome = dispatcher.dispatch(
      ScheduledTaskWakeCommand(
        scheduleId = spec.scheduleId,
        scheduleRunId = "schedule-run-dispatch",
        triggeredAtEpochMs = 4_000L,
        triggerReason = ScheduledTaskTriggerReasons.ALARM,
      ),
    )

    assertEquals(ScheduledTaskRunResult.ACCEPTED, outcome.result)
    assertEquals(1, session.submittedTasks.size)
    assertEquals(1, session.ensureProcessingCount)
    assertEquals(listOf(spec.scheduleId), registrar.syncedScheduleIds)
    assertEquals(
      RunSubmissionSources.SCHEDULED_TRIGGER,
      session.submittedTasks.single().metadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE],
    )
    assertEquals(
      spec.scheduleId,
      session.submittedTasks.single().metadata[ScheduledTaskMetadataKeys.SCHEDULE_ID],
    )
    assertEquals(
      ScheduledTaskRunResult.ACCEPTED,
      runRecordStore.get("schedule-run-dispatch")?.result,
    )
    val messages = checkNotNull(chatStore.loadSession(sessionId)).messages
    assertEquals(baselineMessageCount + 2, messages.size)
    assertEquals(spec.payload.prompt, messages[messages.lastIndex - 1].text)
    assertEquals("Thinking…", messages.last().text)
  }

  @Test
  fun scheduledTaskDispatcherSkipsBusySessionWhenConflictPolicyRequiresIt() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-dispatch-busy")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val runRecordStore = InMemoryScheduledTaskRunRecordStoreFactory().create()
    val session = RecordingScheduledTaskSessionAccess(
      sessionId = sessionId,
      hasPendingWork = true,
    )
    val registrar = RecordingScheduledTriggerRegistrar()
    val spec = scheduledTaskSpec(
      sessionId = sessionId,
      scheduleId = "schedule-busy",
      conflictPolicy = ScheduledConflictPolicy.SKIP_IF_SESSION_BUSY,
    )
    specStore.upsert(spec)
    val baselineMessageCount = checkNotNull(chatStore.loadSession(sessionId)).messages.size
    val dispatcher = ScheduledTaskDispatcher(
      hostAccess = RecordingScheduledRuntimeHostAccess(session),
      chatSessionStore = chatStore,
      safetySettingsFacade = EmptySafetySettingsFacade,
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = emptySet(),
          summary = "workspace=/workspace",
        )
      },
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      localizedContext = ContextWrapper(null),
      assistantPlaceholderTextProvider = { "Thinking…" },
      specStore = specStore,
      runRecordStore = runRecordStore,
      triggerRegistrar = registrar,
      clock = { 6_000L },
    )

    val outcome = dispatcher.dispatch(
      ScheduledTaskWakeCommand(
        scheduleId = spec.scheduleId,
        scheduleRunId = "schedule-run-busy",
        triggeredAtEpochMs = 5_000L,
        triggerReason = ScheduledTaskTriggerReasons.ALARM,
      ),
    )

    assertEquals(ScheduledTaskRunResult.SKIPPED_SESSION_BUSY, outcome.result)
    assertTrue(session.submittedTasks.isEmpty())
    assertEquals(listOf(spec.scheduleId), registrar.syncedScheduleIds)
    assertEquals(
      ScheduledTaskRunResult.SKIPPED_SESSION_BUSY,
      runRecordStore.get("schedule-run-busy")?.result,
    )
    assertEquals(
      baselineMessageCount,
      checkNotNull(chatStore.loadSession(sessionId)).messages.size,
    )
  }

  @Test
  fun parseScheduledTaskWakeCommandRequiresCompleteScheduledWakeIntent() {
    val parsed = parseScheduledTaskWakeCommand(
      action = ACTION_RUN_SCHEDULED_TASK,
      scheduleId = "schedule-intent",
      scheduleRunId = "schedule-run-intent",
      triggeredAtEpochMs = 7_000L,
      triggerReason = ScheduledTaskTriggerReasons.MANUAL,
      targetSessionId = "session-intent",
    )

    assertNotNull(parsed)
    assertEquals("schedule-intent", parsed?.scheduleId)
    assertEquals("schedule-run-intent", parsed?.scheduleRunId)
    assertEquals(7_000L, parsed?.triggeredAtEpochMs)
    assertEquals(ScheduledTaskTriggerReasons.MANUAL, parsed?.triggerReason)
    assertEquals("session-intent", parsed?.targetSessionId)
    assertNull(
      parseScheduledTaskWakeCommand(
        action = ACTION_RUN_SCHEDULED_TASK,
        scheduleId = "schedule-intent",
        scheduleRunId = null,
        triggeredAtEpochMs = null,
        triggerReason = null,
        targetSessionId = null,
      ),
    )
  }

  @Test
  fun resyncEnabledScheduledTasksSyncsOnlyEnabledSpecs() {
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val registrar = RecordingScheduledTriggerRegistrar()
    val triggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create()
    specStore.upsert(
      scheduledTaskSpec(
        sessionId = "session-enabled",
        scheduleId = "schedule-enabled",
      ),
    )
    specStore.upsert(
      scheduledTaskSpec(
        sessionId = "session-disabled",
        scheduleId = "schedule-disabled",
      ).copy(enabled = false, updatedAtEpochMs = 3_000L),
    )

    resyncEnabledScheduledTasks(
      specStore = specStore,
      triggerRegistrar = registrar,
      triggerSyncStateStore = triggerSyncStateStore,
    )

    assertEquals(listOf("schedule-enabled"), registrar.syncedScheduleIds)
    assertEquals(listOf("schedule-disabled"), registrar.cancelledScheduleIds)
    assertEquals(
      linkedSetOf("schedule-enabled"),
      triggerSyncStateStore.loadScheduleIds(),
    )
  }

  @Test
  fun resyncEnabledScheduledTasksCancelsDisabledAndRemovedSchedulesBeforePersistingEnabledIds() {
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val registrar = RecordingScheduledTriggerRegistrar()
    val triggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create()
    triggerSyncStateStore.replaceScheduleIds(
      linkedSetOf("schedule-enabled", "schedule-disabled", "schedule-removed"),
    )
    specStore.upsert(
      scheduledTaskSpec(
        sessionId = "session-enabled",
        scheduleId = "schedule-enabled",
        updatedAtEpochMs = 4_000L,
      ),
    )
    specStore.upsert(
      scheduledTaskSpec(
        sessionId = "session-disabled",
        scheduleId = "schedule-disabled",
        updatedAtEpochMs = 3_000L,
      ).copy(enabled = false, updatedAtEpochMs = 5_000L),
    )

    resyncEnabledScheduledTasks(
      specStore = specStore,
      triggerRegistrar = registrar,
      triggerSyncStateStore = triggerSyncStateStore,
    )

    assertEquals(listOf("schedule-disabled", "schedule-removed"), registrar.cancelledScheduleIds)
    assertEquals(listOf("schedule-enabled"), registrar.syncedScheduleIds)
    assertEquals(
      linkedSetOf("schedule-enabled"),
      triggerSyncStateStore.loadScheduleIds(),
    )
  }

  @Test
  fun resyncEnabledScheduledTasksClearsPersistedIdsWhenNoEnabledSchedulesRemain() {
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val registrar = RecordingScheduledTriggerRegistrar()
    val triggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create()
    triggerSyncStateStore.replaceScheduleIds(
      linkedSetOf("schedule-disabled", "schedule-removed"),
    )
    specStore.upsert(
      scheduledTaskSpec(
        sessionId = "session-disabled",
        scheduleId = "schedule-disabled",
        updatedAtEpochMs = 6_000L,
      ).copy(enabled = false, updatedAtEpochMs = 7_000L),
    )

    resyncEnabledScheduledTasks(
      specStore = specStore,
      triggerRegistrar = registrar,
      triggerSyncStateStore = triggerSyncStateStore,
    )

    assertTrue(registrar.syncedScheduleIds.isEmpty())
    assertEquals(
      listOf("schedule-disabled", "schedule-removed"),
      registrar.cancelledScheduleIds,
    )
    assertTrue(triggerSyncStateStore.loadScheduleIds().isEmpty())
  }

  @Test
  fun plannedRepairWakeCommandsReturnsOnlyDueEnabledSchedules() {
    val dueSpec = scheduledTaskSpec(
      sessionId = "session-due",
      scheduleId = "schedule-due",
    )
    val futureSpec = scheduledTaskSpec(
      sessionId = "session-future",
      scheduleId = "schedule-future",
    ).copy(
      trigger = ScheduledTrigger.RunAtTimestamp(triggerAtEpochMs = 12_000L),
      updatedAtEpochMs = 3_000L,
    )

    val commands = plannedRepairWakeCommands(
      enabledSpecs = listOf(dueSpec, futureSpec),
      nowEpochMs = 10_000L,
      repairReason = ScheduledTaskRepairReasons.BOOT_COMPLETED,
    )

    assertEquals(1, commands.size)
    assertEquals("schedule-due", commands.single().scheduleId)
    assertEquals(ScheduledTaskTriggerReasons.REPAIR, commands.single().triggerReason)
    assertEquals(
      scheduledTaskRunId("schedule-due", 9_000L),
      commands.single().scheduleRunId,
    )
  }

  @Test
  fun scheduledTaskTriggerReasonForRepairMapsPeriodicRepairToRepairTrigger() {
    assertEquals(
      ScheduledTaskTriggerReasons.REPAIR,
      scheduledTaskTriggerReasonForRepair(ScheduledTaskRepairReasons.PERIODIC),
    )
  }

  private fun scheduledTaskSpec(
    sessionId: String,
    scheduleId: String = "schedule-default",
    title: String = "Daily Summary",
    updatedAtEpochMs: Long = 2_000L,
    conflictPolicy: ScheduledConflictPolicy = ScheduledConflictPolicy.ENQUEUE_NEW_RUN,
  ): ScheduledTaskSpec = ScheduledTaskSpec(
    scheduleId = scheduleId,
    sessionId = sessionId,
    title = title,
    enabled = true,
    trigger = ScheduledTrigger.RunAtTimestamp(triggerAtEpochMs = 9_000L),
    payload = ScheduledTaskPayload(prompt = "Summarize the workspace status"),
    policy = ScheduledTaskPolicy(conflictPolicy = conflictPolicy),
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = updatedAtEpochMs,
  )

  private class RecordingScheduledTriggerRegistrar : ScheduledTriggerRegistrar {
    val syncedScheduleIds = mutableListOf<String>()
    val cancelledScheduleIds = mutableListOf<String>()

    override fun syncSpec(spec: ScheduledTaskSpec) {
      syncedScheduleIds += spec.scheduleId
    }

    override fun syncAll(specs: List<ScheduledTaskSpec>) {
      syncedScheduleIds += specs.map(ScheduledTaskSpec::scheduleId)
    }

    override fun cancel(scheduleId: String) {
      cancelledScheduleIds += scheduleId
    }
  }

  private class RecordingScheduledRuntimeHostAccess(
    private val session: RecordingScheduledTaskSessionAccess,
  ) : OpenCrayRuntimeHostAccess {
    override val lifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor()

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun activeWorkSummary(): RuntimeOwnerWorkSummary = RuntimeOwnerWorkSummary()

    override fun session(sessionId: String): OpenCrayRuntimeSessionAccess {
      assertEquals(session.sessionId, sessionId)
      return session
    }

    override fun releaseSession(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit

    override fun runEventJournalStore(sessionId: String): RunEventJournalStore =
      InMemoryRunEventJournalStoreFactory().forChatSession(sessionId)

    override fun promptCheckpointStore(sessionId: String): PromptCheckpointStore =
      InMemoryPromptCheckpointStoreFactory().forChatSession(sessionId)

    override fun supplementStore(sessionId: String): SessionSupplementStore = InMemorySessionSupplementStore()

    override fun markApprovalApproved(
      sessionId: String,
      taskId: String,
      toolName: String?,
      promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
      subAgentApprovalResume: SubAgentApprovalResume?,
    ) = Unit

    override fun markApprovalRejected(
      sessionId: String,
      taskId: String,
      toolName: String?,
      promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
      subAgentApprovalResume: SubAgentApprovalResume?,
    ) = Unit

    override fun clearApproval(sessionId: String, taskId: String) = Unit

    override fun retainKnownApprovalTasks(sessionId: String, taskIds: Set<String>) = Unit

    override fun isApprovalApproved(sessionId: String, taskId: String): Boolean = false

    override fun isApprovalRejected(sessionId: String, taskId: String): Boolean = false
  }

  private class RecordingScheduledTaskSessionAccess(
    override val sessionId: String,
    private val hasPendingWork: Boolean = false,
    private val queueSnapshots: List<SessionQueueTaskSnapshot> = emptyList(),
  ) : OpenCrayRuntimeSessionAccess {
    val submittedTasks = mutableListOf<AgentTask>()
    var ensureProcessingCount: Int = 0
      private set

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission = error("submitPrompt is unused in scheduled task tests.")

    override fun submitTask(task: AgentTask): AgentRunSubmission {
      submittedTasks += task
      return AgentRunSubmission(
        sessionId = sessionId,
        runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: "run-fallback",
        taskId = task.id,
        acceptedAtEpochMs = 8_000L,
      )
    }

    override fun ensureProcessing() {
      ensureProcessingCount += 1
    }

    override fun requestCancel(taskId: String): Boolean = true

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean = false

    override fun listRuns(): List<AgentRunSnapshot> = emptyList()

    override fun findRun(runId: String): AgentRunSnapshot? = null

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = null

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState = SessionLifecycleState.IDLE

    override fun snapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "scheduled-test-agent",
      tasks = queueSnapshots,
      updatedAtEpochMs = 1_000L,
    )

    override fun hasPendingWork(): Boolean = hasPendingWork

    override fun listManagedProcesses(): List<ManagedProcessSnapshot> = emptyList()

    override fun hasLiveManagedProcesses(): Boolean = false

    override fun submitDetachedSubAgentRecoveryTask(
      agentId: String,
      parentRunId: String,
      taskId: String,
      createdAtEpochMs: Long,
      submissionSource: String,
    ): AgentRunSubmission = submitTask(
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

    override fun terminateRunningManagedProcesses(): List<ManagedProcessSnapshot> = emptyList()
  }
}
