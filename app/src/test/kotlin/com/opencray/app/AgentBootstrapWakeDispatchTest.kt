package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBootstrapWakeDispatchTest : AgentBootstrapTestBase() {
  fun defaultWakeDispatcherHandlesApprovalWakeWithoutTouchingDefaultRegistry() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-approval"),
    )
    var chatSnapshotNotificationCount = 0
    val gatewayBundle = testServiceGatewayBundle(
      notifyChatSnapshotsChanged = { chatSnapshotNotificationCount += 1 },
    )
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedTaskIds = mutableListOf<String?>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          RuntimeServiceNotificationCommand.ApproveApproval(
            sessionId = fixture.sessionId,
            taskId = fixture.taskId,
            runId = fixture.runId,
          ),
        )
      },
      approvalNotificationDismisser = { _, command -> dismissedTaskIds += command.taskId },
    )

    dispatcher.dispatch(null)

    assertEquals(1, chatSnapshotNotificationCount)
    assertEquals(listOf(fixture.taskId), dismissedTaskIds)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherHandlesRejectApprovalWakeWithoutTouchingDefaultRegistry() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-reject"),
      cancelRequestResult = true,
    )
    var chatSnapshotNotificationCount = 0
    val gatewayBundle = testServiceGatewayBundle(
      notifyChatSnapshotsChanged = { chatSnapshotNotificationCount += 1 },
    )
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedTaskIds = mutableListOf<String?>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          RuntimeServiceNotificationCommand.RejectApproval(
            sessionId = fixture.sessionId,
            taskId = fixture.taskId,
            runId = fixture.runId,
            executionId = "execution-1",
            executionOrdinal = 1,
          ),
        )
      },
      approvalNotificationDismisser = { _, command -> dismissedTaskIds += command.taskId },
    )

    dispatcher.dispatch(null)

    assertEquals(1, chatSnapshotNotificationCount)
    assertEquals(listOf(fixture.taskId), dismissedTaskIds)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertEquals(listOf(fixture.taskId), fixture.handle.cancelledTaskIds)
    assertTrue(fixture.handle.resumedTaskIds.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherAppliesExecutionBoundApprovalAndConsumesDuplicateTap() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-execution-bound"),
    )
    var chatSnapshotNotificationCount = 0
    val gatewayBundle = testServiceGatewayBundle(
      notifyChatSnapshotsChanged = { chatSnapshotNotificationCount += 1 },
    )
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedCommands = mutableListOf<RuntimeServiceNotificationCommand>()
    val reportedFailures = mutableListOf<Throwable>()
    fun buildDispatcher(): DefaultRuntimeServiceWakeCommandDispatcher =
      DefaultRuntimeServiceWakeCommandDispatcher(
        appContext = context,
        dispatcherDependencies = fixture.serviceHost
          .toRuntimeServiceBootstrapState()
          .wakeCommandDispatcherDependencies,
        gatewayBundle = gatewayBundle,
        projectionCoordinator = projectionCoordinator,
        wakeIntentParser = RuntimeServiceWakeIntentParser {
          RuntimeServiceWakeIntentCommand.Notification(
            RuntimeServiceNotificationCommand.ApproveApproval(
              sessionId = fixture.sessionId,
              taskId = fixture.taskId,
              runId = fixture.runId,
              executionId = "execution-1",
              executionOrdinal = 1,
            ),
          )
        },
        approvalNotificationDismisser = { _, command -> dismissedCommands += command },
        notificationActionFailureReporter = { _, failure -> reportedFailures += failure },
      )

    buildDispatcher().dispatch(null)
    buildDispatcher().dispatch(null)

    assertEquals(2, chatSnapshotNotificationCount)
    assertEquals(2, dismissedCommands.size)
    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertTrue(fixture.handle.cancelledTaskIds.isEmpty())
    assertTrue(reportedFailures.isEmpty())
    assertEquals(2, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherTreatsMismatchedExecutionIdentityAsStaleWithoutStateChange() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-stale-execution"),
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedTaskIds = mutableListOf<String?>()
    val reportedFailures = mutableListOf<Throwable>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost
        .toRuntimeServiceBootstrapState()
        .wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          RuntimeServiceNotificationCommand.ApproveApproval(
            sessionId = fixture.sessionId,
            taskId = fixture.taskId,
            runId = fixture.runId,
            executionId = "execution-999",
            executionOrdinal = 9,
          ),
        )
      },
      approvalNotificationDismisser = { _, command -> dismissedTaskIds += command.taskId },
      notificationActionFailureReporter = { _, failure -> reportedFailures += failure },
    )

    dispatcher.dispatch(null)

    assertEquals(listOf(fixture.taskId), dismissedTaskIds)
    assertTrue(reportedFailures.isEmpty())
    assertTrue(fixture.handle.resumedTaskIds.isEmpty())
    assertTrue(fixture.handle.cancelledTaskIds.isEmpty())
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherTreatsLegacyIntentWithoutExecutionIdentityAsStale() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-legacy-intent"),
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedTaskIds = mutableListOf<String?>()
    val reportedFailures = mutableListOf<Throwable>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost
        .toRuntimeServiceBootstrapState()
        .wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          parseRuntimeNotificationCommand(
            action = RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL,
            sessionId = fixture.sessionId,
            taskId = fixture.taskId,
            runId = fixture.runId,
          ) ?: error("Legacy approval intent must stay parseable."),
        )
      },
      approvalNotificationDismisser = { _, command -> dismissedTaskIds += command.taskId },
      notificationActionFailureReporter = { _, failure -> reportedFailures += failure },
    )

    dispatcher.dispatch(null)

    assertEquals(listOf(fixture.taskId), dismissedTaskIds)
    assertTrue(reportedFailures.isEmpty())
    assertTrue(fixture.handle.resumedTaskIds.isEmpty())
    assertTrue(fixture.handle.cancelledTaskIds.isEmpty())
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherRequiresPreciseSessionRunAndTaskMatchForApprovalRouting() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-precise-routing"),
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedTaskIds = mutableListOf<String?>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          RuntimeServiceNotificationCommand.ApproveApproval(
            sessionId = fixture.sessionId,
            taskId = "notification-task-id",
            runId = fixture.runId,
            executionId = "execution-1",
            executionOrdinal = 1,
          ),
        )
      },
      approvalNotificationDismisser = { _, command -> dismissedTaskIds += command.taskId },
    )

    dispatcher.dispatch(null)

    assertEquals(listOf("notification-task-id"), dismissedTaskIds)
    assertTrue(fixture.handle.resumedTaskIds.isEmpty())
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherForwardsScheduledWakeOutcomeAndPersistsProjection() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(temporaryFolder.newFolder("wake-dispatcher-scheduled"))
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.ScheduledTask(
          ScheduledTaskWakeCommand(
            scheduleId = "missing-schedule",
            scheduleRunId = "schedule-run-alpha",
            triggeredAtEpochMs = 1_234L,
            triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
          ),
        )
      },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals(1, projectionCoordinator.persistCallCount)
    assertEquals(1, projectionCoordinator.scheduledDispatchOutcomes.size)
    val outcome = projectionCoordinator.scheduledDispatchOutcomes.single()
    assertEquals(ScheduledTaskRunResult.FAILED_MISSING_SPEC, outcome.result)
    assertEquals("missing-schedule", outcome.scheduleId)
    assertEquals("schedule-run-alpha", outcome.scheduleRunId)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherHandlesResumeWakeAndPersistsProjection() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-resume"),
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.ResumeInterruptedRuns(
          repairReason = ScheduledTaskRepairReasons.OWNER_LEASE_EXPIRED,
        )
      },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals(listOf(fixture.sessionId), fixture.resumedSessionIds)
    assertEquals(
      listOf(fixture.sessionId),
      projectionCoordinator.interruptedRunRepairResults.single().resumedSessionIds,
    )
    assertEquals(
      ScheduledTaskRepairReasons.OWNER_LEASE_EXPIRED,
      projectionCoordinator.interruptedRunRepairResults.single().requestedRepairReason,
    )
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertTrue(fixture.handle.cancelledTaskIds.isEmpty())
    assertTrue(fixture.handle.resumedTaskIds.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherHandlesRepairWakeAndPersistsProjection() {
    val context = MinimalContext()
    val fixture = scheduledRepairWakeDispatcherFixture(
      root = temporaryFolder.newFolder("wake-dispatcher-repair"),
      nowEpochMs = 2_000L,
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.RepairSchedules(ScheduledTaskRepairReasons.WORK_MANAGER)
      },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    val runRecord = fixture.serviceHost.scheduledTaskRunRecordStore.list().single()
    assertEquals(fixture.scheduleId, runRecord.scheduleId)
    assertEquals(ScheduledTaskTriggerReasons.REPAIR, runRecord.triggerReason)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherHandlesScheduleNotificationActionAndPersistsProjection() {
    val context = MinimalContext()
    val nowEpochMs = 3_000L
    val fixture = scheduledRepairWakeDispatcherFixture(
      root = temporaryFolder.newFolder("wake-dispatcher-schedule-notification"),
      nowEpochMs = nowEpochMs,
    )
    val dispatcherDependencies = fixture.serviceHost
      .toRuntimeServiceBootstrapState()
      .wakeCommandDispatcherDependencies
      .let { dependencies ->
        dependencies.copy(
          scheduledTaskDispatcherDependencies = dependencies.scheduledTaskDispatcherDependencies.copy(
            assistantPlaceholderTextProvider = { "Thinking..." },
          ),
        )
      }
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedScheduleIds = mutableListOf<String?>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = dispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          RuntimeServiceNotificationCommand.RunScheduleNow(
            sessionId = fixture.sessionId,
            scheduleId = fixture.scheduleId,
          ),
        )
      },
      approvalNotificationDismisser = { _, _ -> },
      scheduleNotificationDismisser = { _, scheduleId ->
        dismissedScheduleIds += scheduleId
      },
      nowEpochMsProvider = { nowEpochMs },
    )

    dispatcher.dispatch(null)

    val runRecord = requireNotNull(
      fixture.serviceHost.scheduledTaskRunRecordStore.get(
        scheduledTaskRunId(fixture.scheduleId, nowEpochMs),
      ),
    )
    assertEquals(fixture.scheduleId, runRecord.scheduleId)
    assertEquals(fixture.sessionId, runRecord.sessionId)
    assertEquals(ScheduledTaskTriggerReasons.MANUAL, runRecord.triggerReason)
    assertEquals(ScheduledTaskRunResult.ACCEPTED, runRecord.result)
    assertEquals(1, fixture.handle.submittedTasks.size)
    assertEquals(
      fixture.scheduleId,
      fixture.handle.submittedTasks.single().metadata[ScheduledTaskMetadataKeys.SCHEDULE_ID],
    )
    assertEquals(1, fixture.handle.ensureProcessingCallCount)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertEquals(1, projectionCoordinator.scheduledDispatchOutcomes.size)
    assertEquals(ScheduledTaskRunResult.ACCEPTED, projectionCoordinator.scheduledDispatchOutcomes.single().result)
    assertEquals(listOf(fixture.scheduleId), dismissedScheduleIds)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherHandlesDisableScheduleNotificationActionAndPersistsProjection() {
    val context = MinimalContext()
    val nowEpochMs = 4_000L
    val fixture = scheduledRepairWakeDispatcherFixture(
      root = temporaryFolder.newFolder("wake-dispatcher-disable-schedule-notification"),
      nowEpochMs = nowEpochMs,
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedScheduleIds = mutableListOf<String?>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          RuntimeServiceNotificationCommand.DisableSchedule(
            sessionId = fixture.sessionId,
            scheduleId = fixture.scheduleId,
          ),
        )
      },
      approvalNotificationDismisser = { _, _ -> },
      scheduleNotificationDismisser = { _, scheduleId ->
        dismissedScheduleIds += scheduleId
      },
      nowEpochMsProvider = { nowEpochMs },
    )

    dispatcher.dispatch(null)

    val disabledSpec = requireNotNull(fixture.serviceHost.scheduledTaskSpecStore.get(fixture.scheduleId))
    assertFalse(disabledSpec.enabled)
    assertEquals(nowEpochMs, disabledSpec.updatedAtEpochMs)
    assertTrue(fixture.handle.submittedTasks.isEmpty())
    assertEquals(0, fixture.handle.ensureProcessingCallCount)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertEquals(listOf(fixture.scheduleId), dismissedScheduleIds)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherHandlesSnoozeScheduleNotificationActionAndPersistsProjection() {
    val context = MinimalContext()
    val nowEpochMs = 5_000L
    val fixture = scheduledRepairWakeDispatcherFixture(
      root = temporaryFolder.newFolder("wake-dispatcher-snooze-schedule-notification"),
      nowEpochMs = nowEpochMs,
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedScheduleIds = mutableListOf<String?>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          RuntimeServiceNotificationCommand.SnoozeSchedule(
            sessionId = fixture.sessionId,
            scheduleId = fixture.scheduleId,
          ),
        )
      },
      approvalNotificationDismisser = { _, _ -> },
      scheduleNotificationDismisser = { _, scheduleId ->
        dismissedScheduleIds += scheduleId
      },
      nowEpochMsProvider = { nowEpochMs },
    )

    dispatcher.dispatch(null)

    val snoozedSpec = requireNotNull(fixture.serviceHost.scheduledTaskSpecStore.get(fixture.scheduleId))
    assertTrue(snoozedSpec.enabled)
    assertEquals(nowEpochMs + SCHEDULED_TASK_NOTIFICATION_SNOOZE_DELAY_MS, snoozedSpec.snoozedUntilEpochMs)
    assertEquals(nowEpochMs, snoozedSpec.updatedAtEpochMs)
    assertTrue(fixture.handle.submittedTasks.isEmpty())
    assertEquals(0, fixture.handle.ensureProcessingCallCount)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertEquals(listOf(fixture.scheduleId), dismissedScheduleIds)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherHandlesChatWriteWakeAndPersistsProjection() {
    val context = MinimalContext()
    var interruptedTaskIdOrRunId: String? = null
    val gatewayBundle = testServiceGatewayBundle(
      interruptChatRun = { identifier ->
        interruptedTaskIdOrRunId = identifier
      },
    )
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = testServiceHost(
        temporaryFolder.newFolder("wake-dispatcher-chat-write"),
      ).toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.ChatWrite(
          OpenCrayChatWriteCommand.InterruptChatRun("run-wake"),
        )
      },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals("run-wake", interruptedTaskIdOrRunId)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherDismissesTerminalNotificationAfterRetryChatWriteWake() {
    val context = MinimalContext()
    var retriedTaskIdOrRunId: String? = null
    val dismissedTerminalTaskIds = mutableListOf<String?>()
    val gatewayBundle = testServiceGatewayBundle(
      retryChatRun = { identifier ->
        retriedTaskIdOrRunId = identifier
      },
    )
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = testServiceHost(
        temporaryFolder.newFolder("wake-dispatcher-chat-write-retry"),
      ).toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.ChatWrite(
          command = OpenCrayChatWriteCommand.RetryChatRun("run-retry-wake"),
          terminalNotificationTaskId = "task-retry-wake",
        )
      },
      approvalNotificationDismisser = { _, _ -> },
      terminalNotificationDismisser = { _, _, taskId ->
        dismissedTerminalTaskIds += taskId
      },
    )

    dispatcher.dispatch(null)

    assertEquals("run-retry-wake", retriedTaskIdOrRunId)
    assertEquals(listOf("task-retry-wake"), dismissedTerminalTaskIds)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherTreatsStaleNotificationApprovalActionsAsIdempotent() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(
      temporaryFolder.newFolder("wake-dispatcher-stale-approval"),
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedTaskIds = mutableListOf<String?>()
    val commands = listOf<RuntimeServiceNotificationCommand>(
      RuntimeServiceNotificationCommand.ApproveApproval(
        sessionId = "missing-session",
        taskId = "missing-approve-task",
        runId = "missing-approve-run",
      ),
      RuntimeServiceNotificationCommand.RejectApproval(
        sessionId = "missing-session",
        taskId = "missing-reject-task",
        runId = "missing-reject-run",
      ),
    )

    commands.forEach { command ->
      DefaultRuntimeServiceWakeCommandDispatcher(
        appContext = context,
        dispatcherDependencies = serviceHost
          .toRuntimeServiceBootstrapState()
          .wakeCommandDispatcherDependencies,
        gatewayBundle = gatewayBundle,
        projectionCoordinator = projectionCoordinator,
        wakeIntentParser = RuntimeServiceWakeIntentParser {
          RuntimeServiceWakeIntentCommand.Notification(command)
        },
        approvalNotificationDismisser = { _, command -> dismissedTaskIds += command.taskId },
      ).dispatch(null)
    }

    assertEquals(
      listOf("missing-approve-task", "missing-reject-task"),
      dismissedTaskIds,
    )
    assertEquals(2, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherKeepsApprovalNotificationWhenResumeFails() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      root = temporaryFolder.newFolder("wake-dispatcher-approval-resume-failure"),
      resumeRequestResult = false,
    )
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedTaskIds = mutableListOf<String?>()
    val reportedFailures = mutableListOf<Throwable>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost
        .toRuntimeServiceBootstrapState()
        .wakeCommandDispatcherDependencies,
      gatewayBundle = testServiceGatewayBundle(),
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          RuntimeServiceNotificationCommand.ApproveApproval(
            sessionId = fixture.sessionId,
            taskId = fixture.taskId,
            runId = fixture.runId,
            executionId = "execution-1",
            executionOrdinal = 1,
          ),
        )
      },
      approvalNotificationDismisser = { _, command -> dismissedTaskIds += command.taskId },
      notificationActionFailureReporter = { _, failure -> reportedFailures += failure },
    )

    val failure = runCatching { dispatcher.dispatch(null) }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(dismissedTaskIds.isEmpty())
    assertEquals(1, reportedFailures.size)
    assertEquals(1, projectionCoordinator.persistCallCount)
  }

  @Test
  fun defaultWakeDispatcherKeepsScheduleNotificationWhenRunNowFails() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(
      temporaryFolder.newFolder("wake-dispatcher-schedule-action-failure"),
    )
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedScheduleIds = mutableListOf<String?>()
    val reportedFailures = mutableListOf<Throwable>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = serviceHost
        .toRuntimeServiceBootstrapState()
        .wakeCommandDispatcherDependencies,
      gatewayBundle = testServiceGatewayBundle(),
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          RuntimeServiceNotificationCommand.RunScheduleNow(
            sessionId = "session-missing-schedule",
            scheduleId = "missing-schedule-action",
          ),
        )
      },
      approvalNotificationDismisser = { _, _ -> },
      scheduleNotificationDismisser = { _, scheduleId ->
        dismissedScheduleIds += scheduleId
      },
      notificationActionFailureReporter = { _, failure -> reportedFailures += failure },
      nowEpochMsProvider = { 6_000L },
    )

    dispatcher.dispatch(null)

    assertTrue(dismissedScheduleIds.isEmpty())
    assertEquals(1, reportedFailures.size)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertEquals(
      ScheduledTaskRunResult.FAILED_MISSING_SPEC,
      projectionCoordinator.scheduledDispatchOutcomes.single().result,
    )
  }

  @Test
  fun defaultWakeDispatcherPersistsProjectionButKeepsTerminalNotificationWhenRetryChatWriteFails() {
    val context = MinimalContext()
    val dismissedTerminalTaskIds = mutableListOf<String?>()
    val gatewayBundle = testServiceGatewayBundle(
      retryChatRun = {
        error("retry dispatch failed")
      },
    )
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = testServiceHost(
        temporaryFolder.newFolder("wake-dispatcher-chat-write-retry-failure"),
      ).toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.ChatWrite(
          command = OpenCrayChatWriteCommand.RetryChatRun("run-retry-failed-wake"),
          terminalNotificationTaskId = "task-retry-failed-wake",
        )
      },
      approvalNotificationDismisser = { _, _ -> },
      terminalNotificationDismisser = { _, _, taskId ->
        dismissedTerminalTaskIds += taskId
      },
    )

    val failure = runCatching {
      dispatcher.dispatch(null)
    }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(dismissedTerminalTaskIds.isEmpty())
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherSkipsWriteWhenOwnerLeaseIsNotHeld() {
    val context = MinimalContext()
    var interruptedTaskIdOrRunId: String? = null
    val gatewayBundle = testServiceGatewayBundle(
      interruptChatRun = { identifier ->
        interruptedTaskIdOrRunId = identifier
      },
    )
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator(
      ownerLeaseAcquired = false,
    )
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = testServiceHost(
        temporaryFolder.newFolder("wake-dispatcher-non-owner"),
      ).toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.ChatWrite(
          OpenCrayChatWriteCommand.InterruptChatRun("run-wake"),
        )
      },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals(1, projectionCoordinator.ownerLeaseAcquireCallCount)
    assertNull(interruptedTaskIdOrRunId)
    assertEquals(0, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherNoOpsForUnknownWakeAction() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-unknown"),
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser { null },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals(0, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertTrue(fixture.resumedSessionIds.isEmpty())
    assertTrue(fixture.handle.cancelledTaskIds.isEmpty())
    assertTrue(fixture.handle.resumedTaskIds.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherNoOpsForMalformedScheduledWake() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-malformed-scheduled"),
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        parseScheduledTaskWakeCommand(
          action = ACTION_RUN_SCHEDULED_TASK,
          scheduleId = "   ",
          scheduleRunId = "schedule-run-alpha",
          triggeredAtEpochMs = 1_234L,
          triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
          targetSessionId = null,
        )?.let(RuntimeServiceWakeIntentCommand::ScheduledTask)
      },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals(0, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertTrue(fixture.resumedSessionIds.isEmpty())
    assertTrue(fixture.handle.cancelledTaskIds.isEmpty())
    assertTrue(fixture.handle.resumedTaskIds.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultBinderEndpointHandlesApprovalWriteWithoutTouchingDefaultRegistry() {
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("binder-endpoint-approval"),
    )
    var chatSnapshotNotificationCount = 0
    val gatewayBundle = testServiceGatewayBundle(
      notifyChatSnapshotsChanged = { chatSnapshotNotificationCount += 1 },
    )
    val shellStateAccess = RecordingRuntimeServiceShellStateAccess()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val endpoint = DefaultRuntimeServiceBinderEndpoint(
      binderEndpointDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().binderEndpointDependencies,
      gatewayBundle = gatewayBundle,
      shellStateAccess = shellStateAccess,
      projectionCoordinator = projectionCoordinator,
    )

    val dispatch = endpoint.dispatchChatWriteCommand(
      OpenCrayChatWriteCommand.ApproveChatApproval(fixture.taskId),
    )

    assertEquals(OpenCrayChatWriteDispatchResult.Completed, dispatch)
    assertEquals(1, chatSnapshotNotificationCount)
    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultBinderEndpointDelegatesGenericChatWriteAndPersistsProjection() {
    val serviceHost = testServiceHost(temporaryFolder.newFolder("binder-endpoint-chat"))
    var refreshSandboxSessionInfoCallCount = 0
    val gatewayBundle = testServiceGatewayBundle(
      refreshSandboxSessionInfo = { refreshSandboxSessionInfoCallCount += 1 },
    )
    val shellStateAccess = RecordingRuntimeServiceShellStateAccess()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val endpoint = DefaultRuntimeServiceBinderEndpoint(
      binderEndpointDependencies = serviceHost.toRuntimeServiceBootstrapState().binderEndpointDependencies,
      gatewayBundle = gatewayBundle,
      shellStateAccess = shellStateAccess,
      projectionCoordinator = projectionCoordinator,
    )

    val dispatch = endpoint.dispatchChatWriteCommand(
      OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
    )

    assertEquals(OpenCrayChatWriteDispatchResult.Completed, dispatch)
    assertEquals(1, refreshSandboxSessionInfoCallCount)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultBinderEndpointRejectsChatWriteWhenOwnerLeaseIsNotHeld() {
    val serviceHost = testServiceHost(temporaryFolder.newFolder("binder-endpoint-non-owner-chat"))
    var refreshSandboxSessionInfoCallCount = 0
    val gatewayBundle = testServiceGatewayBundle(
      refreshSandboxSessionInfo = { refreshSandboxSessionInfoCallCount += 1 },
    )
    val shellStateAccess = RecordingRuntimeServiceShellStateAccess()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator(
      ownerLeaseAcquired = false,
    )
    val endpoint = DefaultRuntimeServiceBinderEndpoint(
      binderEndpointDependencies = serviceHost.toRuntimeServiceBootstrapState().binderEndpointDependencies,
      gatewayBundle = gatewayBundle,
      shellStateAccess = shellStateAccess,
      projectionCoordinator = projectionCoordinator,
    )
    var failureMessage: String? = null

    try {
      endpoint.dispatchChatWriteCommand(
        OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
      )
    } catch (expected: IllegalStateException) {
      failureMessage = expected.message
    }

    assertEquals(
      "Runtime service target 'interactive' does not hold the active owner lease.",
      failureMessage,
    )
    assertEquals(1, projectionCoordinator.ownerLeaseAcquireCallCount)
    assertEquals(0, refreshSandboxSessionInfoCallCount)
    assertEquals(0, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultBinderEndpointRoutesChatWriteThroughResolvedServiceTarget() {
    val serviceHost = testServiceHost(temporaryFolder.newFolder("binder-endpoint-forwarded-chat"))
    var localRefreshSandboxSessionInfoCallCount = 0
    val forwardedCommands = mutableListOf<OpenCrayChatWriteCommand>()
    val forwardedClient = object : OpenCrayRuntimeServiceClient {
      override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot =
        OpenCrayRuntimeServiceClientSnapshot(
          connectionState = RuntimeServiceConnectionState.binderConnected(),
        )

      override fun peekConnectionState(): RuntimeServiceConnectionState =
        RuntimeServiceConnectionState.binderConnected()

      override fun dispatchChatWriteCommand(
        command: OpenCrayChatWriteCommand,
      ): OpenCrayChatWriteDispatchResult {
        forwardedCommands += command
        return OpenCrayChatWriteDispatchResult.Completed
      }
    }
    val gatewayBundle = testServiceGatewayBundle(
      refreshSandboxSessionInfo = { localRefreshSandboxSessionInfoCallCount += 1 },
    )
    val shellStateAccess = RecordingRuntimeServiceShellStateAccess()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val endpoint = DefaultRuntimeServiceBinderEndpoint(
      binderEndpointDependencies = serviceHost.toRuntimeServiceBootstrapState().binderEndpointDependencies.copy(
        chatWriteTargetResolver = ChatRuntimeWriteTargetResolver {
          RuntimeServiceTarget.DETACHED_BACKGROUND
        },
        targetScopedServiceClientProvider = { forwardedClient },
      ),
      gatewayBundle = gatewayBundle,
      shellStateAccess = shellStateAccess,
      projectionCoordinator = projectionCoordinator,
    )

    val dispatch = endpoint.dispatchChatWriteCommand(
      OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
    )

    assertEquals(OpenCrayChatWriteDispatchResult.Completed, dispatch)
    assertEquals(0, localRefreshSandboxSessionInfoCallCount)
    assertEquals(
      listOf(OpenCrayChatWriteCommand.RefreshSandboxSessionInfo),
      forwardedCommands,
    )
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  fun defaultBinderEndpointDoesNotPersistProjectionForSkillsOrSettingsWrites() {
    val serviceHost = testServiceHost(temporaryFolder.newFolder("binder-endpoint-settings-skills"))
    var refreshSkillsCallCount = 0
    val notificationPayloads = mutableListOf<Map<String, Any?>>()
    val gatewayBundle = testServiceGatewayBundle(
      refreshSkills = {
        refreshSkillsCallCount += 1
        "skills refreshed"
      },
      saveNotificationSettings = { payload ->
        notificationPayloads += payload
        mapOf("saved" to true)
      },
    )
    val shellStateAccess = RecordingRuntimeServiceShellStateAccess()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val endpoint = DefaultRuntimeServiceBinderEndpoint(
      binderEndpointDependencies = serviceHost.toRuntimeServiceBootstrapState().binderEndpointDependencies,
      gatewayBundle = gatewayBundle,
      shellStateAccess = shellStateAccess,
      projectionCoordinator = projectionCoordinator,
    )

    val skillsDispatch = endpoint.dispatchSkillsWriteCommand(
      OpenCraySkillsWriteCommand.RefreshSkills,
    )
    val settingsDispatch = endpoint.dispatchSettingsWriteCommand(
      OpenCraySettingsWriteCommand.SaveNotificationSettings(
        payload = mapOf("enabled" to true),
      ),
    )

    assertEquals(OpenCraySkillsWriteDispatchResult.Message("skills refreshed"), skillsDispatch)
    assertEquals(
      OpenCraySettingsWriteDispatchResult.Payload(mapOf("saved" to true)),
      settingsDispatch,
    )
    assertEquals(1, refreshSkillsCallCount)
    assertEquals(listOf(mapOf("enabled" to true)), notificationPayloads)
    assertEquals(0, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }
}
