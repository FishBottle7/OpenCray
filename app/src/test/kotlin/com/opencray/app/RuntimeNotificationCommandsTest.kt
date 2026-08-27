package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeNotificationCommandsTest {
  @Test
  fun parseRuntimeNotificationCommandParsesApprovalActions() {
    val approve = parseRuntimeNotificationCommand(
      action = RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL,
      sessionId = "session-a",
      taskId = "task-a",
      runId = "run-a",
    )
    val reject = parseRuntimeNotificationCommand(
      action = RuntimeNotificationIntentActions.ACTION_REJECT_RUNTIME_APPROVAL,
      sessionId = "session-b",
      taskId = "task-b",
      runId = null,
    )

    assertEquals(
      RuntimeServiceNotificationCommand.ApproveApproval(
        sessionId = "session-a",
        taskId = "task-a",
        runId = "run-a",
      ),
      approve,
    )
    assertEquals(
      RuntimeServiceNotificationCommand.RejectApproval(
        sessionId = "session-b",
        taskId = "task-b",
        runId = null,
      ),
      reject,
    )
  }

  @Test
  fun parseRuntimeNotificationCommandCarriesExecutionIdentityForApprovalActions() {
    val command = parseRuntimeNotificationCommand(
      action = RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL,
      sessionId = "session-execution",
      taskId = "task-execution",
      runId = "run-execution",
      executionId = " execution-7 ",
      executionOrdinal = 3,
    )

    assertEquals(
      RuntimeServiceNotificationCommand.ApproveApproval(
        sessionId = "session-execution",
        taskId = "task-execution",
        runId = "run-execution",
        executionId = "execution-7",
        executionOrdinal = 3,
      ),
      command,
    )
  }

  @Test
  fun parseRuntimeNotificationCommandFromLegacyIntentHasNoExecutionIdentity() {
    val command = parseRuntimeNotificationCommand(
      action = RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL,
      sessionId = "session-old",
      taskId = "task-old",
      runId = "run-old",
    )

    assertEquals(
      RuntimeServiceNotificationCommand.ApproveApproval(
        sessionId = "session-old",
        taskId = "task-old",
        runId = "run-old",
      ),
      command,
    )
  }

  @Test
  fun parseRuntimeNotificationCommandParsesScheduleActions() {
    val runNow = parseRuntimeNotificationCommand(
      action = RuntimeNotificationIntentActions.ACTION_RUN_SCHEDULE_NOW,
      sessionId = "session-schedule",
      taskId = null,
      runId = null,
      scheduleId = " schedule-now ",
    )
    val disable = parseRuntimeNotificationCommand(
      action = RuntimeNotificationIntentActions.ACTION_DISABLE_SCHEDULE,
      sessionId = "session-schedule",
      taskId = null,
      runId = null,
      scheduleId = " schedule-disable ",
    )
    val snooze = parseRuntimeNotificationCommand(
      action = RuntimeNotificationIntentActions.ACTION_SNOOZE_SCHEDULE,
      sessionId = "session-schedule",
      taskId = null,
      runId = null,
      scheduleId = " schedule-snooze ",
    )

    assertEquals(
      RuntimeServiceNotificationCommand.RunScheduleNow(
        sessionId = "session-schedule",
        scheduleId = "schedule-now",
      ),
      runNow,
    )
    assertEquals(
      RuntimeServiceNotificationCommand.DisableSchedule(
        sessionId = "session-schedule",
        scheduleId = "schedule-disable",
      ),
      disable,
    )
    assertEquals(
      RuntimeServiceNotificationCommand.SnoozeSchedule(
        sessionId = "session-schedule",
        scheduleId = "schedule-snooze",
      ),
      snooze,
    )
  }

  @Test
  fun parseRuntimeNotificationCommandRejectsMissingTargetsAndUnknownActions() {
    assertNull(
      parseRuntimeNotificationCommand(
        action = RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL,
        sessionId = "session-c",
        taskId = null,
        runId = null,
      ),
    )
    assertNull(
      parseRuntimeNotificationCommand(
        action = "noop",
        sessionId = "session-c",
        taskId = null,
        runId = "run-c",
      ),
    )
    assertNull(
      parseRuntimeNotificationCommand(
        action = RuntimeNotificationIntentActions.ACTION_RUN_SCHEDULE_NOW,
        sessionId = "session-c",
        taskId = null,
        runId = null,
        scheduleId = "   ",
      ),
    )
    assertNull(
      parseRuntimeNotificationCommand(
        action = RuntimeNotificationIntentActions.ACTION_DISABLE_SCHEDULE,
        sessionId = "session-c",
        taskId = null,
        runId = null,
        scheduleId = null,
      ),
    )
    assertNull(
      parseRuntimeNotificationCommand(
        action = RuntimeNotificationIntentActions.ACTION_SNOOZE_SCHEDULE,
        sessionId = "session-c",
        taskId = null,
        runId = null,
        scheduleId = "",
      ),
    )
  }
}
