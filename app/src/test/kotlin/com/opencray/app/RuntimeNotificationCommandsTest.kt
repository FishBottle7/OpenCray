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
  fun parseRuntimeNotificationCommandParsesScheduleActions() {
    val command = parseRuntimeNotificationCommand(
      action = RuntimeNotificationIntentActions.ACTION_RUN_SCHEDULE_NOW,
      sessionId = "session-schedule",
      taskId = null,
      runId = null,
      scheduleId = " schedule-now ",
    )

    assertEquals(
      RuntimeServiceNotificationCommand.RunScheduleNow(
        sessionId = "session-schedule",
        scheduleId = "schedule-now",
      ),
      command,
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
  }
}
