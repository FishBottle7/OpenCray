package com.opencray.app

import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBootstrapIntentsTest : AgentBootstrapTestBase() {
  @Test
  fun runtimeServiceTargetForNotificationTaskDefaultsInteractiveForRegularTasks() {
    val target = runtimeServiceTargetForNotificationTask(notificationTargetTestTask())

    assertEquals(RuntimeServiceTarget.INTERACTIVE, target)
  }

  @Test
  fun runtimeServiceTargetForNotificationTaskUsesDetachedBackgroundForScheduledTasks() {
    val target = runtimeServiceTargetForNotificationTask(
      notificationTargetTestTask(
        metadata = mapOf(ScheduledTaskMetadataKeys.SCHEDULE_ID to "schedule-1"),
      ),
    )

    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, target)
  }

  @Test
  fun runtimeServiceTargetForNotificationTaskUsesDetachedBackgroundForDetachedControlTasks() {
    val target = runtimeServiceTargetForNotificationTask(
      notificationTargetTestTask(
        metadata = mapOf(
          METADATA_SYNTHETIC_SUBAGENT_TASK_KIND to SYNTHETIC_SUBAGENT_TASK_KIND_RECOVERY_WAIT,
        ),
      ),
    )

    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, target)
  }

  @Test
  fun defaultWakeIntentParserMapsResumeActionToCommand() {
    val parser = DefaultRuntimeServiceWakeIntentParser()

    val parsed = parser.parse(
      RecordingCommandIntent()
        .setAction(ACTION_RESUME_INTERRUPTED_RUNS)
        .putExtra(
          EXTRA_REPAIR_REASON,
          ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
        ),
    )

    assertEquals(
      RuntimeServiceWakeIntentCommand.ResumeInterruptedRuns(
        repairReason = ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
      ),
      parsed,
    )
  }

  @Test
  fun defaultIntentDescriptorParserMarksResetAsForegroundResetWithoutWakeCommand() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      actionReader = { ACTION_RESET_RUNTIME },
      forceRuntimeResetReader = { false },
    ).parse(null)

    assertNull(parsed.wakeCommand)
    assertTrue(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserPreservesResumeWakeWhileHonoringForceReset() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      actionReader = { ACTION_RESUME_INTERRUPTED_RUNS },
      forceRuntimeResetReader = { true },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.ResumeInterruptedRuns(
        repairReason = ScheduledTaskRepairReasons.WORK_MANAGER,
      ),
      parsed.wakeCommand,
    )
    assertTrue(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserReadsRuntimeTargetEnvelope() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
    ).parse(
      RecordingCommandIntent().putExtra(
        EXTRA_RUNTIME_SERVICE_TARGET,
        RuntimeServiceTarget.INTERACTIVE.wireValue,
      ),
    )

    assertEquals(RuntimeServiceTarget.INTERACTIVE, parsed.runtimeTarget)
  }

  @Test
  fun defaultWakeIntentParserDefaultsBlankRepairReason() {
    val parser = DefaultRuntimeServiceWakeIntentParser(
      descriptorParser = DefaultRuntimeServiceIntentDescriptorParser(
        notificationCommandParser = { null },
        scheduledTaskWakeCommandParser = { null },
        actionReader = { ACTION_REPAIR_SCHEDULES },
        repairReasonReader = { "   " },
      ),
    )

    val parsed = parser.parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.RepairSchedules(
        ScheduledTaskRepairReasons.WORK_MANAGER,
      ),
      parsed,
    )
  }

  @Test
  fun defaultIntentDescriptorParserDefaultsBlankRepairReason() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      actionReader = { ACTION_REPAIR_SCHEDULES },
      repairReasonReader = { "   " },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.RepairSchedules(ScheduledTaskRepairReasons.WORK_MANAGER),
      parsed.wakeCommand,
    )
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserMarksChatWriteWakeAsForegroundBootstrap() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT },
      actionReader = { ACTION_DISPATCH_CHAT_WRITE },
      chatWriteIdentifierReader = { "run-transport" },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.ChatWrite(
        OpenCrayChatWriteCommand.InterruptChatRun("run-transport"),
      ),
      parsed.wakeCommand,
    )
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserCarriesTerminalNotificationTaskForChatWriteWake() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_CHAT_WRITE_RETRY_RUN },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT },
      actionReader = { ACTION_DISPATCH_CHAT_WRITE },
      chatWriteIdentifierReader = { "run-terminal-retry" },
      notificationTaskIdReader = { "task-terminal-retry" },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.ChatWrite(
        command = OpenCrayChatWriteCommand.RetryChatRun("run-terminal-retry"),
        terminalNotificationTaskId = "task-terminal-retry",
      ),
      parsed.wakeCommand,
    )
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserMarksScheduleNotificationWakeAsForegroundBootstrap() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_RUN_SCHEDULE_NOW },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT },
      actionReader = { RuntimeNotificationIntentActions.ACTION_RUN_SCHEDULE_NOW },
      scheduleIdReader = { "schedule-foreground" },
      notificationSessionIdReader = { "session-foreground" },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.Notification(
        RuntimeServiceNotificationCommand.RunScheduleNow(
          sessionId = "session-foreground",
          scheduleId = "schedule-foreground",
        ),
      ),
      parsed.wakeCommand,
    )
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserMarksDisableScheduleNotificationWakeAsForegroundBootstrap() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_DISABLE_SCHEDULE },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT },
      actionReader = { RuntimeNotificationIntentActions.ACTION_DISABLE_SCHEDULE },
      scheduleIdReader = { "schedule-disable-foreground" },
      notificationSessionIdReader = { "session-disable-foreground" },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.Notification(
        RuntimeServiceNotificationCommand.DisableSchedule(
          sessionId = "session-disable-foreground",
          scheduleId = "schedule-disable-foreground",
        ),
      ),
      parsed.wakeCommand,
    )
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserMarksSnoozeScheduleNotificationWakeAsForegroundBootstrap() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_SNOOZE_SCHEDULE },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT },
      actionReader = { RuntimeNotificationIntentActions.ACTION_SNOOZE_SCHEDULE },
      scheduleIdReader = { "schedule-snooze-foreground" },
      notificationSessionIdReader = { "session-snooze-foreground" },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.Notification(
        RuntimeServiceNotificationCommand.SnoozeSchedule(
          sessionId = "session-snooze-foreground",
          scheduleId = "schedule-snooze-foreground",
        ),
      ),
      parsed.wakeCommand,
    )
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserMarksCancelScheduledRunNotificationWakeAsForegroundBootstrap() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT },
      actionReader = { RuntimeNotificationIntentActions.ACTION_CANCEL_SCHEDULED_RUN },
      chatWriteIdentifierReader = { "run-scheduled-cancel" },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.ChatWrite(
        OpenCrayChatWriteCommand.InterruptChatRun("run-scheduled-cancel"),
      ),
      parsed.wakeCommand,
    )
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserMapsCancelScheduledRunActionToChatWriteWake() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { null },
      commandVersionReader = { 0 },
      actionReader = { RuntimeNotificationIntentActions.ACTION_CANCEL_SCHEDULED_RUN },
      chatWriteIdentifierReader = { "task-scheduled-cancel" },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.ChatWrite(
        OpenCrayChatWriteCommand.InterruptChatRun("task-scheduled-cancel"),
      ),
      parsed.wakeCommand,
    )
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserPrefersExplicitCommandKindEnvelope() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_SCHEDULED_TASK },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT },
      actionReader = { ACTION_RESET_RUNTIME },
      scheduleIdReader = { "schedule-1" },
      scheduleRunIdReader = { "run-1" },
      triggeredAtEpochMsReader = { 5L },
      triggerReasonReader = { "alarm" },
      targetSessionIdReader = { null },
      forceRuntimeResetReader = { false },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.ScheduledTask(
        ScheduledTaskWakeCommand(
          scheduleId = "schedule-1",
          scheduleRunId = "run-1",
          triggeredAtEpochMs = 5L,
          triggerReason = "alarm",
          targetSessionId = null,
        ),
      ),
      parsed.wakeCommand,
    )
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun runtimeServiceIntentFactoryRoutesWakeIntentsToTargetOwnedComponents() {
    val resolvedTargets = mutableListOf<RuntimeServiceTarget>()
    val factory = RuntimeServiceIntentFactory(
      componentProvider = RuntimeServiceComponentProvider { _, target ->
        resolvedTargets += target
        android.content.ComponentName("com.opencray.test", "RuntimeService")
      },
      intentBuilder = RuntimeServiceIntentBuilder { _, _ ->
        RecordingCommandIntent()
      },
    )
    val context = MinimalContext()

    factory.baseIntent(
      context = context,
      target = RuntimeServiceTarget.INTERACTIVE,
    )
    factory.scheduledTaskIntent(
      context = context,
      command = ScheduledTaskWakeCommand(
        scheduleId = "detached-schedule",
        scheduleRunId = "detached-run",
        triggeredAtEpochMs = 10L,
        triggerReason = "alarm",
        targetSessionId = null,
      ),
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )

    assertEquals(
      listOf(
        RuntimeServiceTarget.INTERACTIVE,
        RuntimeServiceTarget.DETACHED_BACKGROUND,
      ),
      resolvedTargets,
    )
    assertEquals(
      OpenCrayAgentRuntimeService::class.java,
      runtimeServiceClassForTarget(RuntimeServiceTarget.INTERACTIVE),
    )
    assertEquals(
      OpenCrayDetachedRuntimeService::class.java,
      runtimeServiceClassForTarget(RuntimeServiceTarget.DETACHED_BACKGROUND),
    )
  }

  @Test
  fun targetedRuntimeServicesRejectIntentsOwnedByTheOtherRuntimeProcess() {
    val interactiveIntent = RecordingCommandIntent().apply {
      putExtra(
        EXTRA_RUNTIME_SERVICE_TARGET,
        RuntimeServiceTarget.INTERACTIVE.wireValue,
      )
    }
    val detachedIntent = RecordingCommandIntent().apply {
      putExtra(
        EXTRA_RUNTIME_SERVICE_TARGET,
        RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue,
      )
    }
    val invalidIntent = RecordingCommandIntent().apply {
      putExtra(EXTRA_RUNTIME_SERVICE_TARGET, "unknown-runtime-target")
    }
    val interactiveService = OpenCrayAgentRuntimeService()
    val detachedService = OpenCrayDetachedRuntimeService()

    assertTrue(interactiveService.acceptsRuntimeIntent(null))
    assertTrue(detachedService.acceptsRuntimeIntent(null))
    assertTrue(interactiveService.acceptsRuntimeIntent(interactiveIntent))
    assertTrue(detachedService.acceptsRuntimeIntent(detachedIntent))
    assertFalse(interactiveService.acceptsRuntimeIntent(detachedIntent))
    assertFalse(detachedService.acceptsRuntimeIntent(interactiveIntent))
    assertFalse(interactiveService.acceptsRuntimeIntent(invalidIntent))
    assertFalse(detachedService.acceptsRuntimeIntent(invalidIntent))
  }

  @Test
  fun rejectedRuntimeServiceStartStopsCurrentStartBeforeReturningNotSticky() {
    val stoppedStartIds = mutableListOf<Int>()

    val result = rejectedRuntimeServiceStartResult(
      startId = 42,
      stopSelf = stoppedStartIds::add,
    )

    assertEquals(Service.START_NOT_STICKY, result)
    assertEquals(listOf(42), stoppedStartIds)
  }

  @Test
  fun runtimeServiceIntentFactoryWritesCommandEnvelopeMetadata() {
    val factory = RuntimeServiceIntentFactory(
      componentProvider = RuntimeServiceComponentProvider { _, _ ->
        android.content.ComponentName("com.opencray.test", "RuntimeService")
      },
      intentBuilder = RuntimeServiceIntentBuilder { _, _ ->
        RecordingCommandIntent()
      },
    )
    val context = MinimalContext()

    val scheduledIntent = factory.scheduledTaskIntent(
      context = context,
      command = ScheduledTaskWakeCommand(
        scheduleId = "schedule-1",
        scheduleRunId = "run-1",
        triggeredAtEpochMs = 9L,
        triggerReason = "alarm",
        targetSessionId = "session-1",
      ),
    )
    val interactiveBaseIntent = factory.baseIntent(
      context = context,
      target = RuntimeServiceTarget.INTERACTIVE,
    )
    val resetIntent = factory.resetRuntimeIntent(
      context = context,
      repairReason = "repair",
    )
    val chatWriteIntent = factory.chatWriteIntent(
      context = context,
      command = OpenCrayChatWriteCommand.InterruptChatRun("run-transport"),
      target = RuntimeServiceTarget.INTERACTIVE,
    )
    val approvalIntent = factory.approvalActionIntent(
      context = context,
      action = RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL,
      sessionId = "session-1",
      taskId = "task-1",
      runId = "run-1",
    )
    val scheduleActionIntent = factory.scheduleNotificationActionIntent(
      context = context,
      action = RuntimeNotificationIntentActions.ACTION_RUN_SCHEDULE_NOW,
      scheduleId = "schedule-1",
      sessionId = "session-1",
    )

    assertEquals(
      RUNTIME_SERVICE_COMMAND_VERSION_CURRENT,
      scheduledIntent.getIntExtra(EXTRA_RUNTIME_SERVICE_COMMAND_VERSION, 0),
    )
    assertEquals(
      COMMAND_KIND_SCHEDULED_TASK,
      scheduledIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      COMMAND_KIND_RESET_RUNTIME,
      resetIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      COMMAND_KIND_APPROVE_APPROVAL,
      approvalIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      COMMAND_KIND_RUN_SCHEDULE_NOW,
      scheduleActionIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    val disableScheduleActionIntent = factory.scheduleNotificationActionIntent(
      context = context,
      action = RuntimeNotificationIntentActions.ACTION_DISABLE_SCHEDULE,
      scheduleId = "schedule-disable-1",
      sessionId = "session-disable-1",
    )
    assertEquals(
      COMMAND_KIND_DISABLE_SCHEDULE,
      disableScheduleActionIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    val snoozeScheduleActionIntent = factory.scheduleNotificationActionIntent(
      context = context,
      action = RuntimeNotificationIntentActions.ACTION_SNOOZE_SCHEDULE,
      scheduleId = "schedule-snooze-1",
      sessionId = "session-snooze-1",
    )
    val cancelScheduledRunActionIntent = factory.scheduleNotificationActionIntent(
      context = context,
      action = RuntimeNotificationIntentActions.ACTION_CANCEL_SCHEDULED_RUN,
      scheduleId = "schedule-cancel-1",
      sessionId = "session-cancel-1",
      taskId = "task-cancel-1",
      runId = "run-cancel-1",
    )
    assertEquals(
      COMMAND_KIND_SNOOZE_SCHEDULE,
      snoozeScheduleActionIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN,
      cancelScheduledRunActionIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      "run-cancel-1",
      cancelScheduledRunActionIntent.getStringExtra(EXTRA_CHAT_WRITE_IDENTIFIER),
    )
    assertEquals(
      COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN,
      chatWriteIntent?.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      "run-transport",
      chatWriteIntent?.getStringExtra(EXTRA_CHAT_WRITE_IDENTIFIER),
    )
    assertEquals(
      DEFAULT_RUNTIME_SERVICE_TARGET.wireValue,
      scheduledIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_TARGET),
    )
    assertEquals(
      RuntimeServiceTarget.INTERACTIVE.wireValue,
      interactiveBaseIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_TARGET),
    )
    assertEquals(
      RuntimeServiceTarget.INTERACTIVE.wireValue,
      chatWriteIntent?.getStringExtra(EXTRA_RUNTIME_SERVICE_TARGET),
    )
    assertEquals(
      "schedule-1",
      scheduleActionIntent.getStringExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SCHEDULE_ID),
    )
  }

  @Test
  fun runtimeServiceIntentFactoryRoundTripsScheduleNotificationActions() {
    val factory = RuntimeServiceIntentFactory(
      componentProvider = RuntimeServiceComponentProvider { _, _ ->
        android.content.ComponentName("com.opencray.test", "RuntimeService")
      },
      intentBuilder = RuntimeServiceIntentBuilder { _, _ ->
        RecordingCommandIntent()
      },
    )
    val parser = DefaultRuntimeServiceWakeIntentParser()
    val context = MinimalContext()

    val runNowIntent = factory.scheduleNotificationActionIntent(
      context = context,
      action = RuntimeNotificationIntentActions.ACTION_RUN_SCHEDULE_NOW,
      scheduleId = "schedule-action",
      sessionId = "session-action",
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )
    val disableIntent = factory.scheduleNotificationActionIntent(
      context = context,
      action = RuntimeNotificationIntentActions.ACTION_DISABLE_SCHEDULE,
      scheduleId = "schedule-disable-action",
      sessionId = "session-disable-action",
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )
    val snoozeIntent = factory.scheduleNotificationActionIntent(
      context = context,
      action = RuntimeNotificationIntentActions.ACTION_SNOOZE_SCHEDULE,
      scheduleId = "schedule-snooze-action",
      sessionId = "session-snooze-action",
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )
    val cancelRunIntent = factory.scheduleNotificationActionIntent(
      context = context,
      action = RuntimeNotificationIntentActions.ACTION_CANCEL_SCHEDULED_RUN,
      scheduleId = "schedule-cancel-action",
      sessionId = "session-cancel-action",
      taskId = "task-cancel-action",
      runId = "run-cancel-action",
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )
    val cancelRunByTaskIntent = factory.scheduleNotificationActionIntent(
      context = context,
      action = RuntimeNotificationIntentActions.ACTION_CANCEL_SCHEDULED_RUN,
      scheduleId = "schedule-cancel-task-action",
      sessionId = "session-cancel-task-action",
      taskId = "task-cancel-task-action",
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )

    assertEquals(
      COMMAND_KIND_RUN_SCHEDULE_NOW,
      runNowIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      RuntimeServiceWakeIntentCommand.Notification(
        RuntimeServiceNotificationCommand.RunScheduleNow(
          sessionId = "session-action",
          scheduleId = "schedule-action",
        ),
      ),
      parser.parse(runNowIntent),
    )
    assertEquals(
      COMMAND_KIND_DISABLE_SCHEDULE,
      disableIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      RuntimeServiceWakeIntentCommand.Notification(
        RuntimeServiceNotificationCommand.DisableSchedule(
          sessionId = "session-disable-action",
          scheduleId = "schedule-disable-action",
        ),
      ),
      parser.parse(disableIntent),
    )
    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue,
      disableIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_TARGET),
    )
    assertEquals(
      COMMAND_KIND_SNOOZE_SCHEDULE,
      snoozeIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      RuntimeServiceWakeIntentCommand.Notification(
        RuntimeServiceNotificationCommand.SnoozeSchedule(
          sessionId = "session-snooze-action",
          scheduleId = "schedule-snooze-action",
        ),
      ),
      parser.parse(snoozeIntent),
    )
    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue,
      snoozeIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_TARGET),
    )
    assertEquals(
      COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN,
      cancelRunIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      RuntimeServiceWakeIntentCommand.ChatWrite(
        OpenCrayChatWriteCommand.InterruptChatRun("run-cancel-action"),
      ),
      parser.parse(cancelRunIntent),
    )
    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue,
      cancelRunIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_TARGET),
    )
    assertEquals(
      RuntimeServiceWakeIntentCommand.ChatWrite(
        OpenCrayChatWriteCommand.InterruptChatRun("task-cancel-task-action"),
      ),
      parser.parse(cancelRunByTaskIntent),
    )
  }

  @Test
  fun runtimeServiceIntentFactoryRoundTripsSupportedChatWriteWakeCommands() {
    val factory = RuntimeServiceIntentFactory(
      componentProvider = RuntimeServiceComponentProvider { _, _ ->
        android.content.ComponentName("com.opencray.test", "RuntimeService")
      },
      intentBuilder = RuntimeServiceIntentBuilder { _, _ ->
        RecordingCommandIntent()
      },
    )
    val parser = DefaultRuntimeServiceWakeIntentParser()
    val context = MinimalContext()
    val supportedCommands = listOf(
      OpenCrayChatWriteCommand.ApproveChatApproval("task-1") to
        COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL,
      OpenCrayChatWriteCommand.ApproveChatApprovalForSession("run-2") to
        COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL_FOR_SESSION,
      OpenCrayChatWriteCommand.RejectChatApproval("task-3") to
        COMMAND_KIND_CHAT_WRITE_REJECT_APPROVAL,
      OpenCrayChatWriteCommand.InterruptChatRun("run-4") to
        COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN,
      OpenCrayChatWriteCommand.RetryChatRun("run-5") to
        COMMAND_KIND_CHAT_WRITE_RETRY_RUN,
    )

    supportedCommands.forEach { (command, commandKind) ->
      val intent = requireNotNull(
        factory.chatWriteIntent(
          context = context,
          command = command,
          target = RuntimeServiceTarget.INTERACTIVE,
        ),
      )

      assertEquals(
        commandKind,
        intent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
      )
      assertEquals(
        RuntimeServiceWakeIntentCommand.ChatWrite(command),
        parser.parse(intent),
      )
      assertEquals(
        RuntimeServiceTarget.INTERACTIVE.wireValue,
        intent.getStringExtra(EXTRA_RUNTIME_SERVICE_TARGET),
      )
    }

    assertNull(
      factory.chatWriteIntent(
        context = context,
        command = OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
        target = RuntimeServiceTarget.INTERACTIVE,
      ),
    )
  }

  @Test
  fun defaultIntentDescriptorParserRejectsChatWriteWakeWithBlankIdentifier() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT },
      actionReader = { ACTION_DISPATCH_CHAT_WRITE },
      chatWriteIdentifierReader = { "   " },
    ).parse(null)

    assertNull(parsed.wakeCommand)
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserRejectsChatWriteWakeWhenCommandVersionMismatches() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT + 1 },
      actionReader = { ACTION_DISPATCH_CHAT_WRITE },
      chatWriteIdentifierReader = { "run-version-mismatch" },
    ).parse(null)

    assertNull(parsed.wakeCommand)
    assertFalse(parsed.requestsRuntimeReset)
    assertFalse(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserRejectsExplicitScheduleActionWhenCommandVersionMismatches() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_RUN_SCHEDULE_NOW },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT + 1 },
      actionReader = { RuntimeNotificationIntentActions.ACTION_RUN_SCHEDULE_NOW },
      scheduleIdReader = { "schedule-version-mismatch" },
      notificationSessionIdReader = { "session-version-mismatch" },
    ).parse(null)

    assertNull(parsed.wakeCommand)
    assertFalse(parsed.requestsRuntimeReset)
    assertFalse(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserRejectsExplicitResetWhenCommandVersionMismatches() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_RESET_RUNTIME },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT + 1 },
      actionReader = { ACTION_RESET_RUNTIME },
      forceRuntimeResetReader = { true },
    ).parse(null)

    assertNull(parsed.wakeCommand)
    assertFalse(parsed.requestsRuntimeReset)
    assertFalse(parsed.requiresBootstrapForeground)
  }
}
