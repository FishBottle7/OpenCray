package com.opencray.app

import android.content.ContextWrapper
import com.opencray.app.facade.safety.EmptySafetySettingsFacade
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.runtime.ScheduledTaskCreateRequest
import com.opencray.runtime.ScheduledTaskDeleteRequest
import com.opencray.runtime.ScheduledTaskGetRequest
import com.opencray.runtime.ScheduledTaskListRequest
import com.opencray.runtime.ScheduledTaskRunNowRequest
import com.opencray.runtime.ScheduledTaskSnoozeRequest
import com.opencray.runtime.ScheduledTaskTriggerRequest
import com.opencray.runtime.ScheduledTaskUpdateRequest
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
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
    ).copy(
      policy = ScheduledTaskPolicy(requiresForegroundNotification = false),
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

    val specStoreFile = runtimeRoot.resolve("scheduled-task-specs-v2.json")
    val runRecordStoreFile = runtimeRoot.resolve("scheduled-task-run-records-v2.json")
    val triggerSyncStateStoreFile = runtimeRoot.resolve("scheduled-task-trigger-sync-state-v2.json")

    val reloadedSpecStore = FileBackedScheduledTaskSpecStoreFactory(runtimeRoot).create()
    val reloadedRunRecordStore = FileBackedScheduledTaskRunRecordStoreFactory(runtimeRoot).create()
    val reloadedTriggerSyncStateStore =
      FileBackedScheduledTaskTriggerSyncStateStoreFactory(runtimeRoot).create()

    assertEquals(listOf("Updated"), reloadedSpecStore.list().map(ScheduledTaskSpec::title))
    assertEquals(1, reloadedSpecStore.listEnabled().size)
    assertTrue(reloadedSpecStore.list().single().policy.requiresForegroundNotification)
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
    assertTrue(specStoreFile.exists())
    assertTrue(runRecordStoreFile.exists())
    assertTrue(triggerSyncStateStoreFile.exists())
    assertFalse(runtimeRoot.resolve("scheduled-task-specs.json").exists())
    assertFalse(runtimeRoot.resolve("scheduled-task-run-records.json").exists())
    assertFalse(runtimeRoot.resolve("scheduled-task-trigger-sync-state.json").exists())
    val specStorePayload = specStoreFile.readText()
    assertTrue(specStorePayload.contains("\"persistenceType\":\"at\""))
    assertFalse(specStorePayload.contains("run_at_timestamp"))
    assertFalse(specStorePayload.contains("run_after_delay"))
  }

  @Test
  fun fileBackedSpecStoreRetainsIndependentConcurrentFieldUpdates() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-runtime-concurrent-update")
    val firstStore = FileBackedScheduledTaskSpecStoreFactory(runtimeRoot).create()
    val secondStore = FileBackedScheduledTaskSpecStoreFactory(runtimeRoot).create()
    val original = scheduledTaskSpec(
      sessionId = "session-concurrent-update",
      scheduleId = "schedule-concurrent-update",
      title = "Original",
      updatedAtEpochMs = 1_000L,
    )
    firstStore.upsert(original)
    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val titleUpdate = executor.submit {
        ready.countDown()
        start.await()
        firstStore.update(original.scheduleId) { current ->
          current.copy(
            title = "Title update",
            updatedAtEpochMs = current.updatedAtEpochMs + 1L,
          )
        }
      }
      val enabledUpdate = executor.submit {
        ready.countDown()
        start.await()
        secondStore.update(original.scheduleId) { current ->
          current.copy(
            enabled = false,
            updatedAtEpochMs = current.updatedAtEpochMs + 1L,
          )
        }
      }
      assertTrue(ready.await(5L, TimeUnit.SECONDS))
      start.countDown()
      titleUpdate.get(5L, TimeUnit.SECONDS)
      enabledUpdate.get(5L, TimeUnit.SECONDS)
    } finally {
      executor.shutdownNow()
    }

    val updated = FileBackedScheduledTaskSpecStoreFactory(runtimeRoot)
      .create()
      .get(original.scheduleId)
    assertNotNull(updated)
    assertEquals("Title update", updated?.title)
    assertFalse(updated?.enabled ?: true)
  }

  @Test
  fun fileBackedScheduledTaskRunRecordRemoveForScheduleUsesSingleStorageUpdate() {
    val storage = StaleReadDurableTextStorage()
    val runRecordStore = fileBackedScheduledTaskRunRecordStore(
      storage = storage,
      clock = { 10_000L },
    )
    runRecordStore.upsert(
      scheduledTaskRunRecord(
        scheduleRunId = "schedule-run-remove",
        scheduleId = "schedule-remove",
        updatedAtEpochMs = 1_000L,
      ),
    )
    runRecordStore.upsert(
      scheduledTaskRunRecord(
        scheduleRunId = "schedule-run-keep",
        scheduleId = "schedule-keep",
        updatedAtEpochMs = 2_000L,
      ),
    )
    val staleBeforeConcurrentWrite = storage.currentText
    runRecordStore.upsert(
      scheduledTaskRunRecord(
        scheduleRunId = "schedule-run-concurrent",
        scheduleId = "schedule-concurrent",
        updatedAtEpochMs = 3_000L,
      ),
    )
    val updateCallsBeforeRemove = storage.updateTextCallCount

    storage.returnStaleTextOnNextRead(staleBeforeConcurrentWrite)
    runRecordStore.removeForSchedule("schedule-remove")

    assertEquals(updateCallsBeforeRemove + 1, storage.updateTextCallCount)
    assertTrue(storage.hasPendingStaleRead)
    storage.clearPendingStaleRead()
    assertNull(runRecordStore.get("schedule-run-remove"))
    assertEquals(
      setOf("schedule-run-keep", "schedule-run-concurrent"),
      runRecordStore.list().map(ScheduledTaskRunRecord::scheduleRunId).toSet(),
    )
  }

  @Test
  fun fileBackedScheduledTaskSpecListUsesSingleStorageUpdate() {
    val storage = StaleReadDurableTextStorage()
    val specStore = fileBackedScheduledTaskSpecStore(
      storage = storage,
      clock = { 10_000L },
    )
    specStore.upsert(
      scheduledTaskSpec(
        sessionId = "session-scheduled-spec",
        scheduleId = "schedule-older",
        title = "Older schedule",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val staleBeforeConcurrentWrite = storage.currentText
    specStore.upsert(
      scheduledTaskSpec(
        sessionId = "session-scheduled-spec",
        scheduleId = "schedule-concurrent",
        title = "Concurrent schedule",
        updatedAtEpochMs = 2_000L,
      ),
    )
    val updateCallsBeforeList = storage.updateTextCallCount

    storage.returnStaleTextOnNextRead(staleBeforeConcurrentWrite)
    val specs = specStore.list()

    assertEquals(updateCallsBeforeList + 1, storage.updateTextCallCount)
    assertTrue(storage.hasPendingStaleRead)
    storage.clearPendingStaleRead()
    assertEquals(
      listOf("Concurrent schedule", "Older schedule"),
      specs.map(ScheduledTaskSpec::title),
    )
    assertEquals(
      listOf("schedule-concurrent", "schedule-older"),
      specStore.list().map(ScheduledTaskSpec::scheduleId),
    )
  }

  @Test
  fun fileBackedScheduledTaskRunRecordListUsesSingleStorageUpdate() {
    val storage = StaleReadDurableTextStorage()
    val runRecordStore = fileBackedScheduledTaskRunRecordStore(
      storage = storage,
      clock = { 10_000L },
    )
    runRecordStore.upsert(
      scheduledTaskRunRecord(
        scheduleRunId = "schedule-run-older",
        scheduleId = "schedule-older",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val staleBeforeConcurrentWrite = storage.currentText
    runRecordStore.upsert(
      scheduledTaskRunRecord(
        scheduleRunId = "schedule-run-concurrent",
        scheduleId = "schedule-concurrent",
        updatedAtEpochMs = 2_000L,
      ),
    )
    val updateCallsBeforeList = storage.updateTextCallCount

    storage.returnStaleTextOnNextRead(staleBeforeConcurrentWrite)
    val records = runRecordStore.list()

    assertEquals(updateCallsBeforeList + 1, storage.updateTextCallCount)
    assertTrue(storage.hasPendingStaleRead)
    storage.clearPendingStaleRead()
    assertEquals(
      listOf("schedule-run-concurrent", "schedule-run-older"),
      records.map(ScheduledTaskRunRecord::scheduleRunId),
    )
    assertEquals(
      listOf("schedule-run-concurrent", "schedule-run-older"),
      runRecordStore.list().map(ScheduledTaskRunRecord::scheduleRunId),
    )
  }

  @Test
  fun appScheduledTaskManagerCanListGetUpdateAndDeleteSchedules() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-manager-runtime-root")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat-store"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = inMemoryScheduledTaskSpecStoreFactory().create()
    val runRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create()
    val triggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create()
    val registrar = RecordingScheduledTriggerRegistrar()
    val startedCommands = mutableListOf<ScheduledTaskWakeCommand>()
    val manager = AppScheduledTaskManager(
      storageRootPath = runtimeRoot.toPath(),
      chatSessionStore = chatStore,
      specStore = specStore,
      runRecordStore = runRecordStore,
      triggerRegistrar = registrar,
      triggerSyncStateStore = triggerSyncStateStore,
      scheduledTaskStarter = { command ->
        startedCommands += command
        true
      },
      clock = { 10_000L },
    )

    val created = manager.create(
      ScheduledTaskCreateRequest(
        sessionId = sessionId,
        prompt = "Summarize the workspace status",
        trigger = ScheduledTaskTriggerRequest.After("PT1M"),
      ),
    )
    runRecordStore.upsert(
      ScheduledTaskRunRecord(
        scheduleRunId = "schedule-run-manager",
        scheduleId = created.scheduleId,
        sessionId = sessionId,
        triggerReason = ScheduledTaskTriggerReasons.ALARM,
        triggeredAtEpochMs = 20_000L,
        acceptedAtEpochMs = 20_100L,
        createdRunId = "run-manager",
        createdTaskId = "task-manager",
        result = ScheduledTaskRunResult.ACCEPTED,
        updatedAtEpochMs = 20_100L,
      ),
    )

    val listed = manager.list(
      ScheduledTaskListRequest(
        sessionId = sessionId,
        limit = 10,
      ),
    )
    assertEquals(1, listed.tasks.size)
    assertEquals(created.scheduleId, listed.tasks.single().scheduleId)
    assertEquals("after", listed.tasks.single().triggerKind)

    val loaded = manager.get(
      ScheduledTaskGetRequest(
        scheduleId = created.scheduleId,
        recentRunLimit = 5,
      ),
    )
    assertEquals("Summarize the workspace status", loaded.task.prompt)
    assertEquals("after", loaded.task.triggerKind)
    assertTrue(loaded.task.foregroundNotificationRequired)
    assertEquals(1, loaded.recentRuns.size)
    assertEquals("run-manager", loaded.recentRuns.single().createdRunId)

    val updated = manager.update(
      ScheduledTaskUpdateRequest(
        scheduleId = created.scheduleId,
        title = "Weekly review",
        trigger = ScheduledTaskTriggerRequest.Recurrence(
          startAt = "2026-04-13T09:00:00+08:00",
          timezone = "Asia/Shanghai",
          rrule = "FREQ=WEEKLY;BYDAY=MO",
        ),
        enabled = false,
        notifyOnCompletion = false,
      ),
    )
    assertEquals("Weekly review", updated.title)
    assertEquals("rrule", updated.triggerKind)
    assertFalse(updated.enabled)

    val refreshed = manager.get(ScheduledTaskGetRequest(scheduleId = created.scheduleId))
    assertEquals("Weekly review", refreshed.task.title)
    assertEquals("rrule", refreshed.task.triggerKind)
    assertFalse(refreshed.task.enabled)
    assertFalse(refreshed.task.notifyOnCompletion)

    val enabled = manager.update(
      ScheduledTaskUpdateRequest(
        scheduleId = created.scheduleId,
        enabled = true,
      ),
    )
    assertTrue(enabled.enabled)

    val runNow = manager.runNow(ScheduledTaskRunNowRequest(created.scheduleId))
    assertEquals(created.scheduleId, runNow.scheduleId)
    assertEquals(1, startedCommands.size)
    assertEquals(ScheduledTaskTriggerReasons.MANUAL, startedCommands.single().triggerReason)
    assertEquals(sessionId, startedCommands.single().targetSessionId)

    val snoozed = manager.snooze(
      ScheduledTaskSnoozeRequest(
        scheduleId = created.scheduleId,
        durationMinutes = 30,
      ),
    )
    assertEquals(1_810_000L, snoozed.snoozedUntilEpochMs)
    assertEquals(1_810_000L, manager.get(ScheduledTaskGetRequest(created.scheduleId)).task.snoozedUntilEpochMs)

    val deleted = manager.delete(
      ScheduledTaskDeleteRequest(
        scheduleId = created.scheduleId,
      ),
    )
    assertEquals(created.scheduleId, deleted.scheduleId)
    assertTrue(specStore.list().isEmpty())
    assertTrue(runRecordStore.list().isEmpty())
    assertTrue(triggerSyncStateStore.loadScheduleIds().isEmpty())
    assertTrue(registrar.cancelledScheduleIds.contains(created.scheduleId))
  }

  @Test
  fun scheduledTaskGatewayReportsEnabledCountBeyondReturnedSlice() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-gateway-count-root")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat-store"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val manager = AppScheduledTaskManager(
      storageRootPath = runtimeRoot.toPath(),
      chatSessionStore = chatStore,
      specStore = inMemoryScheduledTaskSpecStoreFactory().create(),
      runRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
      triggerRegistrar = RecordingScheduledTriggerRegistrar(),
      triggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create(),
      clock = { 10_000L },
    )
    repeat(3) { index ->
      manager.create(
        ScheduledTaskCreateRequest(
          sessionId = sessionId,
          prompt = "Scheduled task $index",
          trigger = ScheduledTaskTriggerRequest.After("PT${index + 1}M"),
          enabled = index < 2,
        ),
      )
    }

    val payload = manager.loadScheduledTasksGatewayMap(limit = 1)

    assertEquals(3, payload["totalCount"])
    assertEquals(2, payload["enabledCount"])
    assertEquals(1, (payload["tasks"] as List<*>).size)
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
  fun concurrentDispatchClaimsScheduleRunExactlyOnceAcrossStoreInstances() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-dispatch-concurrent-claim")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = FileBackedScheduledTaskSpecStoreFactory(runtimeRoot).create()
    val spec = scheduledTaskSpec(
      sessionId = sessionId,
      scheduleId = "schedule-race",
    )
    specStore.upsert(spec)
    val command = ScheduledTaskWakeCommand(
      scheduleId = spec.scheduleId,
      scheduleRunId = scheduledTaskRunId(spec.scheduleId, 4_000L),
      triggeredAtEpochMs = 4_000L,
      triggerReason = ScheduledTaskTriggerReasons.ALARM,
    )
    val firstSession = RecordingScheduledTaskSessionAccess(sessionId = sessionId)
    val secondSession = RecordingScheduledTaskSessionAccess(sessionId = sessionId)

    fun buildDispatcher(session: RecordingScheduledTaskSessionAccess): ScheduledTaskDispatcher =
      ScheduledTaskDispatcher(
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
        runRecordStore = FileBackedScheduledTaskRunRecordStoreFactory(runtimeRoot).create(),
        triggerRegistrar = RecordingScheduledTriggerRegistrar(),
        clock = { 5_000L },
      )

    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    val outcomes: List<ScheduledTaskDispatchOutcome> = try {
      val futures = listOf(firstSession, secondSession).map { session ->
        executor.submit<ScheduledTaskDispatchOutcome> {
          ready.countDown()
          start.await()
          buildDispatcher(session).dispatch(command)
        }
      }
      assertTrue(ready.await(10L, TimeUnit.SECONDS))
      start.countDown()
      futures.map { future -> future.get(60L, TimeUnit.SECONDS) }
    } finally {
      executor.shutdownNow()
    }

    assertEquals(
      setOf(ScheduledTaskRunResult.ACCEPTED, ScheduledTaskRunResult.SKIPPED_DUPLICATE),
      outcomes.map(ScheduledTaskDispatchOutcome::result).toSet(),
    )
    assertEquals(1, firstSession.submittedTasks.size + secondSession.submittedTasks.size)
    assertEquals(1, firstSession.ensureProcessingCount + secondSession.ensureProcessingCount)
    assertEquals(
      ScheduledTaskRunResult.ACCEPTED,
      FileBackedScheduledTaskRunRecordStoreFactory(runtimeRoot).create()
        .get(command.scheduleRunId)?.result,
    )
  }

  @Test
  fun dispatchSkipsReentryWhileRunRecordIsTriggeredWithoutResuming() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-dispatch-triggered-reentry")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val runRecordStore = InMemoryScheduledTaskRunRecordStoreFactory().create()
    val session = RecordingScheduledTaskSessionAccess(sessionId = sessionId)
    val registrar = RecordingScheduledTriggerRegistrar()
    val spec = scheduledTaskSpec(sessionId = sessionId, scheduleId = "schedule-reentry")
    specStore.upsert(spec)
    runRecordStore.upsert(
      ScheduledTaskRunRecord(
        scheduleRunId = "schedule-run-reentry",
        scheduleId = spec.scheduleId,
        sessionId = sessionId,
        triggerReason = ScheduledTaskTriggerReasons.ALARM,
        triggeredAtEpochMs = 4_000L,
        result = ScheduledTaskRunResult.TRIGGERED,
        updatedAtEpochMs = 4_000L,
      ),
    )
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
        scheduleRunId = "schedule-run-reentry",
        triggeredAtEpochMs = 4_500L,
        triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
      ),
    )

    assertEquals(ScheduledTaskRunResult.SKIPPED_DUPLICATE, outcome.result)
    assertEquals(sessionId, outcome.sessionId)
    assertTrue(session.submittedTasks.isEmpty())
    assertEquals(0, session.ensureProcessingCount)
    assertEquals(
      ScheduledTaskRunResult.TRIGGERED,
      runRecordStore.get("schedule-run-reentry")?.result,
    )
    assertEquals(
      baselineMessageCount,
      checkNotNull(chatStore.loadSession(sessionId)).messages.size,
    )
  }

  @Test
  fun dispatchForRepairFinalizesAcceptedOutboxWhenDerivedTaskAlreadyQueued() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-dispatch-repair-finalize")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val runRecordStore = InMemoryScheduledTaskRunRecordStoreFactory().create()
    val registrar = RecordingScheduledTriggerRegistrar()
    val spec = scheduledTaskSpec(sessionId = sessionId, scheduleId = "schedule-outbox")
    specStore.upsert(spec)
    val command = ScheduledTaskWakeCommand(
      scheduleId = spec.scheduleId,
      scheduleRunId = scheduledTaskRunId(spec.scheduleId, 4_000L),
      triggeredAtEpochMs = 4_500L,
      triggerReason = ScheduledTaskTriggerReasons.REPAIR,
    )
    val identifiers = scheduledTaskRunIdentifiers(
      sessionId = sessionId,
      scheduleRunId = command.scheduleRunId,
    )
    val queuedSnapshot = SessionQueueTaskSnapshot(
      enqueueOrder = 1L,
      task = AgentTask(
        id = identifiers.taskId,
        type = AgentTaskType.PROMPT,
        input = spec.payload.prompt,
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = "SCHEDULED_TASK_ALLOW",
        ),
        createdAtEpochMs = 4_500L,
        metadata = mapOf(
          AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to identifiers.runId,
        ),
      ),
      lifecycleState = QueueTaskLifecycleState.RUNNING,
    )
    val session = RecordingScheduledTaskSessionAccess(
      sessionId = sessionId,
      queueSnapshots = listOf(queuedSnapshot),
    )
    runRecordStore.upsert(
      ScheduledTaskRunRecord(
        scheduleRunId = command.scheduleRunId,
        scheduleId = spec.scheduleId,
        sessionId = sessionId,
        triggerReason = ScheduledTaskTriggerReasons.ALARM,
        triggeredAtEpochMs = 4_000L,
        result = ScheduledTaskRunResult.TRIGGERED,
        updatedAtEpochMs = 4_000L,
      ),
    )
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

    val outcome = dispatcher.dispatchForRepair(command)

    assertEquals(ScheduledTaskRunResult.ACCEPTED, outcome.result)
    assertEquals(identifiers.taskId, outcome.createdTaskId)
    assertEquals(identifiers.runId, outcome.createdRunId)
    assertTrue(session.submittedTasks.isEmpty())
    assertEquals(0, session.ensureProcessingCount)
    val record = requireNotNull(runRecordStore.get(command.scheduleRunId))
    assertEquals(ScheduledTaskRunResult.ACCEPTED, record.result)
    assertEquals(identifiers.taskId, record.createdTaskId)
    assertEquals(identifiers.runId, record.createdRunId)
    assertEquals(
      baselineMessageCount,
      checkNotNull(chatStore.loadSession(sessionId)).messages.size,
    )
  }

  @Test
  fun dispatchForRepairResumesInterruptedRunWithDeterministicIdsExactlyOnce() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-dispatch-repair-resume")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val runRecordStore = InMemoryScheduledTaskRunRecordStoreFactory().create()
    val session = RecordingScheduledTaskSessionAccess(sessionId = sessionId)
    val registrar = RecordingScheduledTriggerRegistrar()
    val spec = scheduledTaskSpec(sessionId = sessionId, scheduleId = "schedule-resume")
    specStore.upsert(spec)
    val command = ScheduledTaskWakeCommand(
      scheduleId = spec.scheduleId,
      scheduleRunId = scheduledTaskRunId(spec.scheduleId, 4_000L),
      triggeredAtEpochMs = 4_500L,
      triggerReason = ScheduledTaskTriggerReasons.REPAIR,
    )
    val identifiers = scheduledTaskRunIdentifiers(
      sessionId = sessionId,
      scheduleRunId = command.scheduleRunId,
    )
    runRecordStore.upsert(
      ScheduledTaskRunRecord(
        scheduleRunId = command.scheduleRunId,
        scheduleId = spec.scheduleId,
        sessionId = sessionId,
        triggerReason = ScheduledTaskTriggerReasons.ALARM,
        triggeredAtEpochMs = 4_000L,
        result = ScheduledTaskRunResult.TRIGGERED,
        updatedAtEpochMs = 4_000L,
      ),
    )
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

    val resumed = dispatcher.dispatchForRepair(command)

    assertEquals(ScheduledTaskRunResult.ACCEPTED, resumed.result)
    assertEquals(1, session.submittedTasks.size)
    val submitted = session.submittedTasks.single()
    assertEquals(identifiers.taskId, submitted.id)
    assertEquals(
      identifiers.runId,
      submitted.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID],
    )
    assertEquals(identifiers.runId, resumed.createdRunId)
    assertEquals(identifiers.taskId, resumed.createdTaskId)
    val messages = checkNotNull(chatStore.loadSession(sessionId)).messages
    assertEquals(identifiers.pendingMessageId, messages.last().messageId)
    val accepted = requireNotNull(runRecordStore.get(command.scheduleRunId))
    assertEquals(ScheduledTaskRunResult.ACCEPTED, accepted.result)
    assertEquals(identifiers.taskId, accepted.createdTaskId)
    assertEquals(identifiers.runId, accepted.createdRunId)

    val replayed = dispatcher.dispatchForRepair(command)

    assertEquals(ScheduledTaskRunResult.SKIPPED_DUPLICATE, replayed.result)
    assertEquals(1, session.submittedTasks.size)
  }

  @Test
  fun concurrentRepairDispatchResumesInterruptedRunExactlyOnce() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-repair-concurrent-resume")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = FileBackedScheduledTaskSpecStoreFactory(runtimeRoot).create()
    val spec = scheduledTaskSpec(
      sessionId = sessionId,
      scheduleId = "schedule-repair-race",
    )
    specStore.upsert(spec)
    val command = ScheduledTaskWakeCommand(
      scheduleId = spec.scheduleId,
      scheduleRunId = scheduledTaskRunId(spec.scheduleId, 4_000L),
      triggeredAtEpochMs = 4_500L,
      triggerReason = ScheduledTaskTriggerReasons.REPAIR,
    )
    val identifiers = scheduledTaskRunIdentifiers(
      sessionId = sessionId,
      scheduleRunId = command.scheduleRunId,
    )
    FileBackedScheduledTaskRunRecordStoreFactory(runtimeRoot).create().upsert(
      ScheduledTaskRunRecord(
        scheduleRunId = command.scheduleRunId,
        scheduleId = spec.scheduleId,
        sessionId = sessionId,
        triggerReason = ScheduledTaskTriggerReasons.ALARM,
        triggeredAtEpochMs = 4_000L,
        result = ScheduledTaskRunResult.TRIGGERED,
        updatedAtEpochMs = 4_000L,
      ),
    )
    val firstSession = RecordingScheduledTaskSessionAccess(sessionId = sessionId)
    val secondSession = RecordingScheduledTaskSessionAccess(sessionId = sessionId)

    fun buildDispatcher(session: RecordingScheduledTaskSessionAccess): ScheduledTaskDispatcher =
      ScheduledTaskDispatcher(
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
        runRecordStore = FileBackedScheduledTaskRunRecordStoreFactory(runtimeRoot).create(),
        triggerRegistrar = RecordingScheduledTriggerRegistrar(),
        clock = { 5_000L },
      )

    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    val outcomes: List<ScheduledTaskDispatchOutcome> = try {
      val futures = listOf(firstSession, secondSession).map { session ->
        executor.submit<ScheduledTaskDispatchOutcome> {
          ready.countDown()
          start.await()
          buildDispatcher(session).dispatchForRepair(command)
        }
      }
      assertTrue(ready.await(10L, TimeUnit.SECONDS))
      start.countDown()
      futures.map { future -> future.get(60L, TimeUnit.SECONDS) }
    } finally {
      executor.shutdownNow()
    }

    assertEquals(
      setOf(ScheduledTaskRunResult.ACCEPTED, ScheduledTaskRunResult.SKIPPED_DUPLICATE),
      outcomes.map(ScheduledTaskDispatchOutcome::result).toSet(),
    )
    assertEquals(1, firstSession.submittedTasks.size + secondSession.submittedTasks.size)
    assertEquals(
      identifiers.taskId,
      firstSession.submittedTasks.singleOrNull()?.id
        ?: secondSession.submittedTasks.single().id,
    )
    assertEquals(1, firstSession.ensureProcessingCount + secondSession.ensureProcessingCount)
    val record = requireNotNull(
      FileBackedScheduledTaskRunRecordStoreFactory(runtimeRoot).create()
        .get(command.scheduleRunId),
    )
    assertEquals(ScheduledTaskRunResult.ACCEPTED, record.result)
    assertEquals(identifiers.taskId, record.createdTaskId)
  }

  @Test
  fun repairRecoveryAfterCrashBetweenSubmitAndAcceptDoesNotResubmit() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-dispatch-crash-before-accept")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val runRecordDelegate = InMemoryScheduledTaskRunRecordStoreFactory().create()
    val runRecordStore = CrashInjectingScheduledTaskRunRecordStore(runRecordDelegate)
    val session = RecordingScheduledTaskSessionAccess(sessionId = sessionId)
    val registrar = RecordingScheduledTriggerRegistrar()
    val spec = scheduledTaskSpec(sessionId = sessionId, scheduleId = "schedule-crash")
    specStore.upsert(spec)
    val command = ScheduledTaskWakeCommand(
      scheduleId = spec.scheduleId,
      scheduleRunId = scheduledTaskRunId(spec.scheduleId, 4_000L),
      triggeredAtEpochMs = 4_500L,
      triggerReason = ScheduledTaskTriggerReasons.ALARM,
    )
    val identifiers = scheduledTaskRunIdentifiers(
      sessionId = sessionId,
      scheduleRunId = command.scheduleRunId,
    )
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

    val crashed = runCatching { dispatcher.dispatch(command) }.exceptionOrNull()

    assertNotNull(crashed)
    assertEquals(1, session.submittedTasks.size)
    assertEquals(identifiers.taskId, session.submittedTasks.single().id)
    assertEquals(
      ScheduledTaskRunResult.TRIGGERED,
      runRecordDelegate.get(command.scheduleRunId)?.result,
    )
    var messages = checkNotNull(chatStore.loadSession(sessionId)).messages
    assertEquals(baselineMessageCount + 2, messages.size)
    assertEquals(identifiers.pendingMessageId, messages.last().messageId)

    runRecordStore.crashOnTerminalWrites = false
    val repaired = dispatcher.dispatchForRepair(command)

    assertEquals(ScheduledTaskRunResult.ACCEPTED, repaired.result)
    assertEquals(identifiers.taskId, repaired.createdTaskId)
    assertEquals(identifiers.runId, repaired.createdRunId)
    assertEquals(1, session.submittedTasks.size)
    assertEquals(1, session.ensureProcessingCount)
    val record = requireNotNull(runRecordDelegate.get(command.scheduleRunId))
    assertEquals(ScheduledTaskRunResult.ACCEPTED, record.result)
    assertEquals(identifiers.taskId, record.createdTaskId)
    assertEquals(identifiers.runId, record.createdRunId)
    messages = checkNotNull(chatStore.loadSession(sessionId)).messages
    assertEquals(baselineMessageCount + 2, messages.size)
    assertEquals(identifiers.pendingMessageId, messages.last().messageId)
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
  fun scheduledTaskDispatcherSkipsSessionWithLiveManagedProcessWhenConflictPolicyRequiresIt() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-dispatch-managed-process-busy")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val runRecordStore = InMemoryScheduledTaskRunRecordStoreFactory().create()
    val session = RecordingScheduledTaskSessionAccess(
      sessionId = sessionId,
      hasLiveManagedProcesses = true,
    )
    val registrar = RecordingScheduledTriggerRegistrar()
    val spec = scheduledTaskSpec(
      sessionId = sessionId,
      scheduleId = "schedule-managed-process-busy",
      conflictPolicy = ScheduledConflictPolicy.SKIP_IF_SESSION_BUSY,
    )
    specStore.upsert(spec)
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
      clock = { 6_500L },
    )

    val outcome = dispatcher.dispatch(
      ScheduledTaskWakeCommand(
        scheduleId = spec.scheduleId,
        scheduleRunId = "schedule-run-managed-process-busy",
        triggeredAtEpochMs = 5_500L,
        triggerReason = ScheduledTaskTriggerReasons.ALARM,
      ),
    )

    assertEquals(ScheduledTaskRunResult.SKIPPED_SESSION_BUSY, outcome.result)
    assertTrue(session.submittedTasks.isEmpty())
    assertEquals(
      ScheduledTaskRunResult.SKIPPED_SESSION_BUSY,
      runRecordStore.get("schedule-run-managed-process-busy")?.result,
    )
  }

  @Test
  fun scheduledTaskDispatcherSkipsSessionWithLiveSubAgentWhenConflictPolicyRequiresIt() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-dispatch-live-subagent-busy")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val runRecordStore = InMemoryScheduledTaskRunRecordStoreFactory().create()
    val session = RecordingScheduledTaskSessionAccess(
      sessionId = sessionId,
      hasLiveSubAgentWork = true,
    )
    val registrar = RecordingScheduledTriggerRegistrar()
    val spec = scheduledTaskSpec(
      sessionId = sessionId,
      scheduleId = "schedule-live-subagent-busy",
      conflictPolicy = ScheduledConflictPolicy.SKIP_IF_SESSION_BUSY,
    )
    specStore.upsert(spec)
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
      clock = { 7_000L },
    )

    val outcome = dispatcher.dispatch(
      ScheduledTaskWakeCommand(
        scheduleId = spec.scheduleId,
        scheduleRunId = "schedule-run-live-subagent-busy",
        triggeredAtEpochMs = 6_000L,
        triggerReason = ScheduledTaskTriggerReasons.ALARM,
      ),
    )

    assertEquals(ScheduledTaskRunResult.SKIPPED_SESSION_BUSY, outcome.result)
    assertTrue(session.submittedTasks.isEmpty())
    assertEquals(
      ScheduledTaskRunResult.SKIPPED_SESSION_BUSY,
      runRecordStore.get("schedule-run-live-subagent-busy")?.result,
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
  fun fileBackedTriggerSyncStateStoreResyncLockSerializesConcurrentEntrants() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-trigger-sync-lock")
    val store = FileBackedScheduledTaskTriggerSyncStateStoreFactory(runtimeRoot).create()
    val ready = CountDownLatch(2)
    val release = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    val activeEntrants = AtomicInteger(0)
    val maxConcurrentEntrants = AtomicInteger(0)
    val failures = mutableListOf<Throwable>()

    repeat(2) {
      executor.execute {
        try {
          ready.countDown()
          store.withResyncLock {
            val current = activeEntrants.incrementAndGet()
            maxConcurrentEntrants.updateAndGet { previous -> maxOf(previous, current) }
            release.await(5, TimeUnit.SECONDS)
            activeEntrants.decrementAndGet()
          }
        } catch (error: Throwable) {
          synchronized(failures) {
            failures += error
          }
        }
      }
    }

    assertTrue(ready.await(5, TimeUnit.SECONDS))
    Thread.sleep(100L)
    release.countDown()
    executor.shutdown()
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
    synchronized(failures) {
      if (failures.isNotEmpty()) {
        throw AssertionError("Concurrent trigger sync lock test failed.", failures.first())
      }
    }
    assertEquals(1, maxConcurrentEntrants.get())
  }

  @Test
  fun fileBackedTriggerSyncStateStoreMutationsUseDurableUpdatePath() {
    val storage = StaleReadDurableTextStorage()
    val store = FileBackedScheduledTaskTriggerSyncStateStore(
      storage = storage,
      runtimeRootDirectory = temporaryFolder.newFolder("scheduled-trigger-sync-update"),
    )

    store.replaceScheduleIds(linkedSetOf("schedule-old"))
    val staleSnapshot = storage.currentText
    store.replaceScheduleIds(linkedSetOf("schedule-current"))
    storage.returnStaleTextOnNextRead(staleSnapshot)

    store.replaceScheduleIds(linkedSetOf(" schedule-new ", "", "schedule-other"))

    assertEquals(3, storage.updateTextCallCount)
    assertTrue(storage.hasPendingStaleRead)
    storage.clearPendingStaleRead()
    assertEquals(
      linkedSetOf("schedule-new", "schedule-other"),
      store.loadScheduleIds(),
    )

    store.clear()

    assertEquals(4, storage.updateTextCallCount)
    assertTrue(store.loadScheduleIds().isEmpty())
  }

  @Test
  fun fileBackedTriggerSyncStateStoreTreatsCorruptSnapshotAsEmptyAndRecoversOnReplace() {
    val storage = StaleReadDurableTextStorage()
    val store = FileBackedScheduledTaskTriggerSyncStateStore(
      storage = storage,
      runtimeRootDirectory = temporaryFolder.newFolder("scheduled-trigger-sync-corrupt"),
    )
    storage.writeText("scheduled-task-trigger-sync-state-v2.json", "{ not-json")

    assertTrue(store.loadScheduleIds().isEmpty())

    store.replaceScheduleIds(linkedSetOf("schedule-recovered"))

    assertEquals(linkedSetOf("schedule-recovered"), store.loadScheduleIds())
    assertTrue(storage.currentText.orEmpty().contains("schedule-recovered"))
    assertTrue(!storage.currentText.orEmpty().contains("not-json"))
  }

  @Test
  fun resyncEnabledScheduledTasksExecutesUnderTriggerSyncResyncLock() {
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val registrar = RecordingScheduledTriggerRegistrar()
    val triggerSyncStateStore = LockTrackingScheduledTaskTriggerSyncStateStore()
    specStore.upsert(
      scheduledTaskSpec(
        sessionId = "session-locked",
        scheduleId = "schedule-locked",
      ),
    )

    resyncEnabledScheduledTasks(
      specStore = specStore,
      triggerRegistrar = registrar,
      triggerSyncStateStore = triggerSyncStateStore,
    )

    assertEquals(1, triggerSyncStateStore.withResyncLockCallCount)
    assertEquals(0, triggerSyncStateStore.activeLockDepth)
    assertEquals(
      listOf("load", "replace"),
      triggerSyncStateStore.events,
    )
    assertEquals(listOf("schedule-locked"), registrar.syncedScheduleIds)
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
      trigger = ScheduledTrigger.At(atEpochMs = 12_000L),
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
  fun plannedRepairWakeCommandsSkipsSnoozedSchedulesUntilSnoozeExpires() {
    val snoozed = scheduledTaskSpec(
      sessionId = "session-snoozed",
      scheduleId = "schedule-snoozed",
    ).copy(
      snoozedUntilEpochMs = 20_000L,
      updatedAtEpochMs = 11_000L,
    )

    val beforeExpiry = plannedRepairWakeCommands(
      enabledSpecs = listOf(snoozed),
      nowEpochMs = 15_000L,
      repairReason = ScheduledTaskRepairReasons.PERIODIC,
    )
    val afterExpiry = plannedRepairWakeCommands(
      enabledSpecs = listOf(snoozed),
      nowEpochMs = 20_000L,
      repairReason = ScheduledTaskRepairReasons.PERIODIC,
    )

    assertTrue(beforeExpiry.isEmpty())
    assertEquals(1, afterExpiry.size)
    assertEquals(
      scheduledTaskRunId("schedule-snoozed", 20_000L),
      afterExpiry.single().scheduleRunId,
    )
  }

  @Test
  fun scheduledTaskTriggerReasonForRepairMapsPeriodicRepairToRepairTrigger() {
    assertEquals(
      ScheduledTaskTriggerReasons.REPAIR,
      scheduledTaskTriggerReasonForRepair(ScheduledTaskRepairReasons.PERIODIC),
    )
  }

  @Test
  fun disableScheduledTaskDisablesSpecAndCancelsRegisteredTrigger() {
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val registrar = RecordingScheduledTriggerRegistrar()
    val triggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create()
    specStore.upsert(
      scheduledTaskSpec(
        sessionId = "session-disable",
        scheduleId = "schedule-disable",
        updatedAtEpochMs = 2_000L,
      ),
    )
    specStore.upsert(
      scheduledTaskSpec(
        sessionId = "session-other",
        scheduleId = "schedule-other",
        updatedAtEpochMs = 3_000L,
      ),
    )
    triggerSyncStateStore.replaceScheduleIds(linkedSetOf("schedule-disable", "schedule-other"))

    val disabled = disableScheduledTask(
      scheduleId = " schedule-disable ",
      specStore = specStore,
      triggerRegistrar = registrar,
      triggerSyncStateStore = triggerSyncStateStore,
      nowEpochMs = 2_500L,
    )

    assertTrue(disabled)
    val disabledSpec = requireNotNull(specStore.get("schedule-disable"))
    assertFalse(disabledSpec.enabled)
    assertEquals(2_500L, disabledSpec.updatedAtEpochMs)
    assertEquals(listOf("schedule-disable"), registrar.cancelledScheduleIds)
    assertEquals(listOf("schedule-other"), registrar.syncedScheduleIds)
    assertEquals(linkedSetOf("schedule-other"), triggerSyncStateStore.loadScheduleIds())
  }

  @Test
  fun snoozeScheduledTaskPersistsDeferralAndResyncsRegisteredTrigger() {
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val registrar = RecordingScheduledTriggerRegistrar()
    val triggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create()
    specStore.upsert(
      scheduledTaskSpec(
        sessionId = "session-snooze",
        scheduleId = "schedule-snooze",
        updatedAtEpochMs = 2_000L,
      ),
    )
    triggerSyncStateStore.replaceScheduleIds(linkedSetOf("schedule-snooze"))

    val snoozed = snoozeScheduledTask(
      scheduleId = " schedule-snooze ",
      snoozedUntilEpochMs = 10_000L,
      specStore = specStore,
      triggerRegistrar = registrar,
      triggerSyncStateStore = triggerSyncStateStore,
      nowEpochMs = 4_000L,
    )

    assertTrue(snoozed)
    val spec = requireNotNull(specStore.get("schedule-snooze"))
    assertTrue(spec.enabled)
    assertEquals(10_000L, spec.snoozedUntilEpochMs)
    assertEquals(4_000L, spec.updatedAtEpochMs)
    assertEquals(listOf("schedule-snooze"), registrar.syncedScheduleIds)
    assertEquals(linkedSetOf("schedule-snooze"), triggerSyncStateStore.loadScheduleIds())
  }

  @Test
  fun defaultScheduledTriggerRegistrarAlsoSchedulesWorkManagerWakeFallback() {
    val alarmScheduler = RecordingScheduledAlarmScheduler()
    val workScheduler = RecordingScheduledWorkScheduler()
    val registrar = DefaultScheduledTriggerRegistrar(
      alarmScheduler = alarmScheduler,
      workScheduler = workScheduler,
      clock = { 5_000L },
    )
    val spec = scheduledTaskSpec(
      sessionId = "session-work-fallback",
      scheduleId = "schedule-work-fallback",
    ).copy(
      trigger = ScheduledTrigger.At(atEpochMs = 12_000L),
      updatedAtEpochMs = 5_000L,
    )

    registrar.syncSpec(spec)

    assertEquals(
      listOf(ScheduledAlarmRequest("schedule-work-fallback", 12_000L, true)),
      alarmScheduler.scheduledRequests,
    )
    assertEquals(
      listOf("schedule-work-fallback" to 12_000L),
      workScheduler.scheduledWakeRequests,
    )
  }

  @Test
  fun defaultScheduledTriggerRegistrarSchedulesSnoozedSpecAtDeferralTime() {
    val alarmScheduler = RecordingScheduledAlarmScheduler()
    val workScheduler = RecordingScheduledWorkScheduler()
    val registrar = DefaultScheduledTriggerRegistrar(
      alarmScheduler = alarmScheduler,
      workScheduler = workScheduler,
      clock = { 5_000L },
    )
    val spec = scheduledTaskSpec(
      sessionId = "session-snooze-sync",
      scheduleId = "schedule-snooze-sync",
    ).copy(
      snoozedUntilEpochMs = 10_000L,
      updatedAtEpochMs = 5_000L,
    )

    registrar.syncSpec(spec)

    assertEquals(
      listOf(ScheduledAlarmRequest("schedule-snooze-sync", 10_000L, true)),
      alarmScheduler.scheduledRequests,
    )
    assertEquals(
      listOf("schedule-snooze-sync" to 10_000L),
      workScheduler.scheduledWakeRequests,
    )
  }

  @Test
  fun scheduledTaskDispatcherClearsExpiredSnoozeBeforeDispatching() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-dispatch-snooze")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val runRecordStore = InMemoryScheduledTaskRunRecordStoreFactory().create()
    val session = RecordingScheduledTaskSessionAccess(sessionId = sessionId)
    val registrar = RecordingScheduledTriggerRegistrar()
    val spec = scheduledTaskSpec(
      sessionId = sessionId,
      scheduleId = "schedule-dispatch-snooze",
    ).copy(
      snoozedUntilEpochMs = 8_000L,
      updatedAtEpochMs = 4_000L,
    )
    specStore.upsert(spec)
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
      assistantPlaceholderTextProvider = { "Thinking..." },
      specStore = specStore,
      runRecordStore = runRecordStore,
      triggerRegistrar = registrar,
      clock = { 8_000L },
    )

    val outcome = dispatcher.dispatch(
      ScheduledTaskWakeCommand(
        scheduleId = spec.scheduleId,
        scheduleRunId = scheduledTaskRunId(spec.scheduleId, 8_000L),
        triggeredAtEpochMs = 8_000L,
        triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
      ),
    )

    assertEquals(ScheduledTaskRunResult.ACCEPTED, outcome.result)
    val persisted = requireNotNull(specStore.get(spec.scheduleId))
    assertNull(persisted.snoozedUntilEpochMs)
    assertEquals(8_000L, persisted.updatedAtEpochMs)
    assertEquals(1, session.submittedTasks.size)
  }

  @Test
  fun scheduledTaskDispatcherSkipsStaleWakeWhileScheduleIsSnoozed() {
    val runtimeRoot = temporaryFolder.newFolder("scheduled-dispatch-stale-snooze")
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = InMemoryScheduledTaskSpecStoreFactory().create()
    val runRecordStore = InMemoryScheduledTaskRunRecordStoreFactory().create()
    val session = RecordingScheduledTaskSessionAccess(sessionId = sessionId)
    val registrar = RecordingScheduledTriggerRegistrar()
    val spec = scheduledTaskSpec(
      sessionId = sessionId,
      scheduleId = "schedule-stale-snooze",
    ).copy(
      snoozedUntilEpochMs = 8_000L,
      updatedAtEpochMs = 4_000L,
    )
    specStore.upsert(spec)
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
      assistantPlaceholderTextProvider = { "Thinking..." },
      specStore = specStore,
      runRecordStore = runRecordStore,
      triggerRegistrar = registrar,
      clock = { 6_000L },
    )

    val outcome = dispatcher.dispatch(
      ScheduledTaskWakeCommand(
        scheduleId = spec.scheduleId,
        scheduleRunId = scheduledTaskRunId(spec.scheduleId, 5_000L),
        triggeredAtEpochMs = 5_000L,
        triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
      ),
    )

    assertEquals(ScheduledTaskRunResult.SKIPPED_SNOOZED, outcome.result)
    assertEquals("schedule_snoozed", outcome.failureReason)
    assertTrue(session.submittedTasks.isEmpty())
    assertEquals(0, session.ensureProcessingCount)
    assertEquals(listOf(spec.scheduleId), registrar.syncedScheduleIds)
    assertEquals(
      ScheduledTaskRunResult.SKIPPED_SNOOZED,
      runRecordStore.get(scheduledTaskRunId(spec.scheduleId, 5_000L))?.result,
    )
    assertEquals(8_000L, requireNotNull(specStore.get(spec.scheduleId)).snoozedUntilEpochMs)
  }

  @Test
  fun nextScheduledTriggerAtEpochMsSkipsExcludedWeeklyRecurrenceOccurrences() {
    val recurrence = parseScheduledTaskRecurrenceTrigger(
      startAt = "2026-04-13T09:00:00+08:00",
      timezone = "Asia/Shanghai",
      rrule = "FREQ=WEEKLY;BYDAY=MO,TU",
      exdates = listOf("2026-04-20T09:00:00+08:00"),
      rdates = emptyList(),
    )
    val spec = scheduledTaskSpec(
      sessionId = "session-recurrence-next",
      scheduleId = "schedule-recurrence-next",
    ).copy(trigger = recurrence)

    val nextAtEpochMs = nextScheduledTriggerAtEpochMs(
      spec = spec,
      nowEpochMs = parseScheduledTaskAbsoluteEpochMs(
        value = "2026-04-20T09:00:00+08:00",
        fieldName = "test.now",
      ),
    )

    assertEquals(
      parseScheduledTaskAbsoluteEpochMs(
        value = "2026-04-21T09:00:00+08:00",
        fieldName = "test.expected",
      ),
      nextAtEpochMs,
    )
  }

  @Test
  fun dueScheduledTriggerAtEpochMsIncludesAdditionalRecurrenceDates() {
    val recurrence = parseScheduledTaskRecurrenceTrigger(
      startAt = "2026-04-13T09:00:00+08:00",
      timezone = "Asia/Shanghai",
      rrule = "FREQ=WEEKLY;BYDAY=MO,TU",
      exdates = listOf("2026-04-20T09:00:00+08:00"),
      rdates = listOf("2026-04-22T09:00:00+08:00"),
    )
    val spec = scheduledTaskSpec(
      sessionId = "session-recurrence-due",
      scheduleId = "schedule-recurrence-due",
    ).copy(trigger = recurrence)

    val dueAtEpochMs = dueScheduledTriggerAtEpochMs(
      spec = spec,
      nowEpochMs = parseScheduledTaskAbsoluteEpochMs(
        value = "2026-04-22T10:00:00+08:00",
        fieldName = "test.now",
      ),
    )

    assertEquals(
      parseScheduledTaskAbsoluteEpochMs(
        value = "2026-04-22T09:00:00+08:00",
        fieldName = "test.expected",
      ),
      dueAtEpochMs,
    )
  }

  @Test
  fun nextScheduledTriggerAtEpochMsSupportsMonthlyFirstDayRecurrence() {
    val recurrence = parseScheduledTaskRecurrenceTrigger(
      startAt = "2026-05-01T09:00:00+08:00",
      timezone = "Asia/Shanghai",
      rrule = "FREQ=MONTHLY;BYMONTHDAY=1",
      exdates = emptyList(),
      rdates = emptyList(),
    )
    val spec = scheduledTaskSpec(
      sessionId = "session-monthly",
      scheduleId = "schedule-monthly",
    ).copy(trigger = recurrence)

    val nextAtEpochMs = nextScheduledTriggerAtEpochMs(
      spec = spec,
      nowEpochMs = parseScheduledTaskAbsoluteEpochMs(
        value = "2026-05-15T09:00:00+08:00",
        fieldName = "test.now",
      ),
    )

    assertEquals(
      parseScheduledTaskAbsoluteEpochMs(
        value = "2026-06-01T09:00:00+08:00",
        fieldName = "test.expected",
      ),
      nextAtEpochMs,
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
    trigger = ScheduledTrigger.At(atEpochMs = 9_000L),
    payload = ScheduledTaskPayload(prompt = "Summarize the workspace status"),
    policy = ScheduledTaskPolicy(conflictPolicy = conflictPolicy),
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = updatedAtEpochMs,
  )

  private fun scheduledTaskRunRecord(
    scheduleRunId: String,
    scheduleId: String,
    updatedAtEpochMs: Long,
    sessionId: String = "session-scheduled-record",
  ): ScheduledTaskRunRecord = ScheduledTaskRunRecord(
    scheduleRunId = scheduleRunId,
    scheduleId = scheduleId,
    sessionId = sessionId,
    triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
    triggeredAtEpochMs = updatedAtEpochMs,
    result = ScheduledTaskRunResult.TRIGGERED,
    updatedAtEpochMs = updatedAtEpochMs,
  )

  private class StaleReadDurableTextStorage : DurableTextStorage {
    private var text: String? = null
    private var staleReadText: String? = null
    var hasPendingStaleRead: Boolean = false
      private set
    var updateTextCallCount: Int = 0
      private set

    val currentText: String?
      get() = text

    fun returnStaleTextOnNextRead(staleText: String?) {
      this.staleReadText = staleText
      hasPendingStaleRead = true
    }

    fun clearPendingStaleRead() {
      staleReadText = null
      hasPendingStaleRead = false
    }

    override fun readText(name: String): String? {
      if (!hasPendingStaleRead) {
        return text
      }
      hasPendingStaleRead = false
      return staleReadText
    }

    override fun writeText(name: String, text: String) {
      this.text = text
    }

    override fun delete(name: String): Boolean {
      val hadText = text != null
      text = null
      return hadText
    }

    override fun <T> updateText(
      name: String,
      update: (String?) -> DurableTextUpdate<T>,
    ): T {
      updateTextCallCount += 1
      val updated = update(text)
      if (updated.write) {
        text = updated.text
      }
      return updated.result
    }
  }

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

  private class LockTrackingScheduledTaskTriggerSyncStateStore :
    ScheduledTaskTriggerSyncStateStore {
    private var scheduleIds: LinkedHashSet<String> = linkedSetOf()
    val events = mutableListOf<String>()
    var withResyncLockCallCount: Int = 0
      private set
    var activeLockDepth: Int = 0
      private set

    override fun loadScheduleIds(): Set<String> {
      events += "load"
      return LinkedHashSet(scheduleIds)
    }

    override fun replaceScheduleIds(scheduleIds: Set<String>) {
      events += "replace"
      this.scheduleIds = scheduleIds.toCollection(linkedSetOf())
    }

    override fun clear() {
      events += "clear"
      scheduleIds.clear()
    }

    override fun <T> withResyncLock(block: () -> T): T {
      withResyncLockCallCount += 1
      activeLockDepth += 1
      try {
        return block()
      } finally {
        activeLockDepth -= 1
      }
    }
  }

  private class RecordingScheduledAlarmScheduler : ScheduledAlarmScheduler {
    val scheduledRequests = mutableListOf<ScheduledAlarmRequest>()
    val cancelledScheduleIds = mutableListOf<String>()

    override fun schedule(request: ScheduledAlarmRequest) {
      scheduledRequests += request
    }

    override fun cancel(scheduleId: String) {
      cancelledScheduleIds += scheduleId
    }
  }

  private class RecordingScheduledWorkScheduler : ScheduledWorkScheduler {
    val scheduledWakeRequests = mutableListOf<Pair<String, Long>>()
    val cancelledScheduleIds = mutableListOf<String>()
    val repairReasons = mutableListOf<String>()
    val repairRequests = mutableListOf<Pair<String, Long>>()
    var periodicRepairEnsured = false

    override fun scheduleWake(
      scheduleId: String,
      triggerAtEpochMs: Long,
    ) {
      scheduledWakeRequests += scheduleId to triggerAtEpochMs
    }

    override fun cancel(scheduleId: String) {
      cancelledScheduleIds += scheduleId
    }

    override fun enqueueRepair(
      reason: String,
      initialDelayMs: Long,
    ) {
      repairReasons += reason
      repairRequests += reason to initialDelayMs
    }

    override fun ensurePeriodicRepair() {
      periodicRepairEnsured = true
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
      approvedRequestFingerprint: String?,
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

  private class CrashInjectingScheduledTaskRunRecordStore(
    private val delegate: ScheduledTaskRunRecordStore,
  ) : ScheduledTaskRunRecordStore {
    var crashOnTerminalWrites: Boolean = true

    override fun list(): List<ScheduledTaskRunRecord> = delegate.list()

    override fun listForSchedule(scheduleId: String): List<ScheduledTaskRunRecord> =
      delegate.listForSchedule(scheduleId)

    override fun get(scheduleRunId: String): ScheduledTaskRunRecord? = delegate.get(scheduleRunId)

    override fun upsert(record: ScheduledTaskRunRecord) {
      failIfCrashing(record)
      delegate.upsert(record)
    }

    override fun claimRun(
      scheduleRunId: String,
      expectedResult: ScheduledTaskRunResult?,
      next: (current: ScheduledTaskRunRecord?) -> ScheduledTaskRunRecord,
    ): Boolean = delegate.claimRun(scheduleRunId, expectedResult) { current ->
      val updated = next(current)
      failIfCrashing(updated)
      updated
    }

    override fun removeForSchedule(scheduleId: String) {
      delegate.removeForSchedule(scheduleId)
    }

    override fun clear() {
      delegate.clear()
    }

    private fun failIfCrashing(record: ScheduledTaskRunRecord) {
      if (crashOnTerminalWrites &&
        (
          record.result == ScheduledTaskRunResult.ACCEPTED ||
            record.result == ScheduledTaskRunResult.FAILED_DISPATCH
          )
      ) {
        throw IllegalStateException("Simulated crash before durable acceptance.")
      }
    }
  }

  private class RecordingScheduledTaskSessionAccess(
    override val sessionId: String,
    private val hasPendingWork: Boolean = false,
    private val hasLiveManagedProcesses: Boolean = false,
    private val hasLiveSubAgentWork: Boolean = false,
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
      tasks = queueSnapshots + submittedTasks.mapIndexed { index, task ->
        SessionQueueTaskSnapshot(
          enqueueOrder = index + 1L,
          task = task,
          lifecycleState = QueueTaskLifecycleState.RUNNING,
        )
      },
      updatedAtEpochMs = 1_000L,
    )

    override fun hasPendingWork(): Boolean = hasPendingWork

    override fun listManagedProcesses(): List<ManagedProcessSnapshot> = emptyList()

    override fun hasLiveManagedProcesses(): Boolean = hasLiveManagedProcesses

    override fun hasLiveSubAgentWork(): Boolean = hasLiveSubAgentWork

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

    override fun terminateRunningManagedProcesses(): List<ManagedProcessSnapshot> = emptyList()
  }
}
