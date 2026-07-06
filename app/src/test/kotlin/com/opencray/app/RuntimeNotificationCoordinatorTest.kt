package com.opencray.app

import com.opencray.app.shell.AppShellDestination
import com.opencray.app.shell.AppShellTab
import com.opencray.app.shell.SettingsSubpage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencray.app.R

class RuntimeNotificationCoordinatorTest {
  @Test
  fun terminalNotificationActionsForInterruptedRunExposeRetryAction() {
    assertEquals(
      listOf(
        RuntimeTerminalNotificationAction(
          command = OpenCrayChatWriteCommand.RetryChatRun("run-terminal"),
          labelResId = R.string.runtime_notification_action_retry,
          runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
          requestKey = "retry-interrupted:session-terminal:task-terminal:run-terminal",
          terminalNotificationTaskId = "task-terminal",
        ),
      ),
      terminalNotificationActionsForModel(terminalModel(interrupted = true)),
    )
  }

  @Test
  fun terminalNotificationActionsForCompletedRunAreEmpty() {
    assertTrue(terminalNotificationActionsForModel(terminalModel(interrupted = false)).isEmpty())
  }

  @Test
  fun scheduleNotificationActionsForAcceptedRunExposeCancelRunAction() {
    val actions = scheduleNotificationActionsForOutcome(
      outcome = ScheduledTaskDispatchOutcome(
        result = ScheduledTaskRunResult.ACCEPTED,
        scheduleId = "schedule-accepted",
        scheduleRunId = "schedule-run-accepted",
        sessionId = "session-accepted",
        createdRunId = "run-accepted",
        createdTaskId = "task-accepted",
      ),
      spec = scheduledSpec(
        scheduleId = "schedule-accepted",
        sessionId = "session-accepted",
      ),
      sessionId = null,
    )

    assertEquals(
      listOf(
        RuntimeScheduleNotificationAction(
          action = RuntimeNotificationIntentActions.ACTION_CANCEL_SCHEDULED_RUN,
          scheduleId = "schedule-accepted",
          sessionId = "session-accepted",
          taskId = "task-accepted",
          runId = "run-accepted",
          labelResId = R.string.runtime_notification_action_cancel_run,
          runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
        ),
      ),
      actions,
    )
  }

  @Test
  fun scheduleNotificationActionsForAcceptedRunWithoutRunIdentifierAreEmpty() {
    val actions = scheduleNotificationActionsForOutcome(
      outcome = ScheduledTaskDispatchOutcome(
        result = ScheduledTaskRunResult.ACCEPTED,
        scheduleId = "schedule-accepted",
        scheduleRunId = "schedule-run-accepted",
        sessionId = "session-accepted",
      ),
      spec = scheduledSpec(
        scheduleId = "schedule-accepted",
        sessionId = "session-accepted",
      ),
      sessionId = null,
    )

    assertTrue(actions.isEmpty())
  }

  @Test
  fun scheduleNotificationActionsForRetryableFailuresKeepScheduleActions() {
    val actions = scheduleNotificationActionsForOutcome(
      outcome = ScheduledTaskDispatchOutcome(
        result = ScheduledTaskRunResult.FAILED_DISPATCH,
        scheduleId = "schedule-failed",
        scheduleRunId = "schedule-run-failed",
        sessionId = "session-failed",
        failureReason = "dispatch_failed",
      ),
      spec = scheduledSpec(
        scheduleId = "schedule-failed",
        sessionId = "session-failed",
      ),
      sessionId = null,
    )

    assertEquals(
      listOf(
        RuntimeNotificationIntentActions.ACTION_RUN_SCHEDULE_NOW,
        RuntimeNotificationIntentActions.ACTION_SNOOZE_SCHEDULE,
        RuntimeNotificationIntentActions.ACTION_DISABLE_SCHEDULE,
      ),
      actions.map(RuntimeScheduleNotificationAction::action),
    )
  }

  @Test
  fun scheduleNotificationOpenDestinationUsesNotificationsBackgroundSettings() {
    assertEquals(
      AppShellDestination(
        selectedTab = AppShellTab.SETTINGS,
        settingsSubpage = SettingsSubpage.NOTIFICATIONS_BACKGROUND,
      ),
      scheduleNotificationOpenDestination(),
    )
  }

  private fun scheduledSpec(
    scheduleId: String,
    sessionId: String,
  ): ScheduledTaskSpec = ScheduledTaskSpec(
    scheduleId = scheduleId,
    sessionId = sessionId,
    title = "Test schedule",
    enabled = true,
    trigger = ScheduledTrigger.After(
      delayMs = 1_000L,
      createdAtEpochMs = 1_000L,
    ),
    payload = ScheduledTaskPayload(prompt = "Run scheduled task."),
    policy = ScheduledTaskPolicy(notifyOnQueued = true),
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_000L,
  )

  private fun terminalModel(
    interrupted: Boolean,
  ): RuntimeTerminalNotificationModel = RuntimeTerminalNotificationModel(
    sessionId = "session-terminal",
    sessionTitle = "Terminal session",
    runId = "run-terminal",
    taskId = "task-terminal",
    runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
    title = "Terminal",
    body = "Terminal body",
    interrupted = interrupted,
  )
}
