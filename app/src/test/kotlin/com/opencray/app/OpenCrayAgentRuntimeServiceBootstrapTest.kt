package com.opencray.app

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenCrayAgentRuntimeServiceBootstrapTest {
  @Before
  fun setUp() {
    clearRuntimeSingletons()
  }

  @After
  fun tearDown() {
    clearRuntimeSingletons()
  }

  @Test
  fun ensureStartedOnlyRequestsServiceStartWithoutCreatingRuntimeHost() {
    val context = RecordingServiceContext()

    OpenCrayAgentRuntimeService.ensureStarted(context)

    val startedIntent = context.startedIntents.single()
    assertEquals(null, startedIntent.action)
    assertEquals(OpenCrayAgentRuntimeService::class.java.name, startedIntent.component?.className)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun startScheduledTaskOnlyRequestsServiceWakeWithoutCreatingRuntimeHost() {
    val context = RecordingServiceContext()
    val command = ScheduledTaskWakeCommand(
      scheduleId = "schedule-alpha",
      scheduleRunId = "schedule-run-alpha",
      triggeredAtEpochMs = 1234L,
      triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
      targetSessionId = "session-alpha",
    )

    OpenCrayAgentRuntimeService.startScheduledTask(context, command)

    val startedIntent = context.startedIntents.single()
    assertEquals(ACTION_RUN_SCHEDULED_TASK, startedIntent.action)
    assertEquals("schedule-alpha", startedIntent.getStringExtra(EXTRA_SCHEDULE_ID))
    assertEquals("schedule-run-alpha", startedIntent.getStringExtra(EXTRA_SCHEDULE_RUN_ID))
    assertEquals(1234L, startedIntent.getLongExtra(EXTRA_TRIGGERED_AT_EPOCH_MS, -1L))
    assertEquals(
      ScheduledTaskTriggerReasons.WORK_MANAGER,
      startedIntent.getStringExtra(EXTRA_TRIGGER_REASON),
    )
    assertEquals("session-alpha", startedIntent.getStringExtra(EXTRA_TARGET_SESSION_ID))
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun repairSchedulesOnlyRequestsServiceWakeWithoutCreatingRuntimeHost() {
    val context = RecordingServiceContext()

    val started = OpenCrayAgentRuntimeService.repairSchedules(
      context = context,
      repairReason = ScheduledTaskRepairReasons.WORK_MANAGER,
    )

    val startedIntent = context.startedIntents.single()
    assertTrue(started)
    assertEquals(ACTION_REPAIR_SCHEDULES, startedIntent.action)
    assertEquals(
      ScheduledTaskRepairReasons.WORK_MANAGER,
      startedIntent.getStringExtra(EXTRA_REPAIR_REASON),
    )
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun resumeInterruptedRunsOnlyRequestsServiceWakeWithoutCreatingRuntimeHost() {
    val context = RecordingServiceContext()

    val started = OpenCrayAgentRuntimeService.resumeInterruptedRuns(
      context = context,
      repairReason = ScheduledTaskRepairReasons.WORK_MANAGER,
    )

    val startedIntent = context.startedIntents.single()
    assertTrue(started)
    assertEquals(ACTION_RESUME_INTERRUPTED_RUNS, startedIntent.action)
    assertEquals(
      ScheduledTaskRepairReasons.WORK_MANAGER,
      startedIntent.getStringExtra(EXTRA_REPAIR_REASON),
    )
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun repairSchedulesReturnsFalseWhenServiceWakeFailsWithoutCreatingRuntimeHost() {
    val context = RecordingServiceContext(throwOnStart = true)

    val started = OpenCrayAgentRuntimeService.repairSchedules(
      context = context,
      repairReason = ScheduledTaskRepairReasons.WORK_MANAGER,
    )

    assertFalse(started)
    assertEquals(1, context.startedIntentAttempts.size)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  private fun clearRuntimeSingletons() {
    OpenCrayRuntimeServiceHostRegistry.clearForTest()
    InProcessOpenCrayRuntimeOwnerRegistry.clearForTest()
  }

  private class RecordingServiceContext(
    private val throwOnStart: Boolean = false,
  ) : ContextWrapper(null) {
    val startedIntents = mutableListOf<Intent>()
    val startedIntentAttempts = mutableListOf<Intent>()
    private val serviceComponent = ComponentName(
      "org.opencray.app",
      OpenCrayAgentRuntimeService::class.java.name,
    )

    override fun getApplicationContext(): Context = this

    override fun getPackageName(): String = "org.opencray.app"

    override fun startService(service: Intent?): ComponentName? {
      return recordStart(service)
    }

    override fun startForegroundService(service: Intent?): ComponentName? {
      return recordStart(service)
    }

    private fun recordStart(service: Intent?): ComponentName? {
      val normalizedIntent = requireNotNull(service) { "Service intent must not be null." }
      startedIntentAttempts += normalizedIntent
      if (throwOnStart) {
        error("synthetic start failure")
      }
      startedIntents += normalizedIntent
      return serviceComponent
    }
  }
}
