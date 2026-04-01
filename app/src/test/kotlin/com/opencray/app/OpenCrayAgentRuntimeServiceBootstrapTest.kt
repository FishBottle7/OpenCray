package com.opencray.app

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenCrayAgentRuntimeServiceBootstrapTest {
  private val recordingStarter = RecordingRuntimeServiceStarter()

  @Before
  fun setUp() {
    clearRuntimeSingletons()
    OpenCrayAgentRuntimeService.setRuntimeServiceStarterForTest(recordingStarter)
  }

  @After
  fun tearDown() {
    OpenCrayAgentRuntimeService.setRuntimeServiceStarterForTest(null)
    clearRuntimeSingletons()
  }

  @Test
  fun ensureStartedOnlyRequestsServiceStartWithoutCreatingRuntimeHost() {
    val context = MinimalContext()

    OpenCrayAgentRuntimeService.ensureStarted(context)

    val startedRequest = recordingStarter.startedRequests.single()
    assertEquals(null, startedRequest.request.action)
    assertFalse(startedRequest.foreground)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun startScheduledTaskOnlyRequestsServiceWakeWithoutCreatingRuntimeHost() {
    val context = MinimalContext()
    val command = ScheduledTaskWakeCommand(
      scheduleId = "schedule-alpha",
      scheduleRunId = "schedule-run-alpha",
      triggeredAtEpochMs = 1234L,
      triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
      targetSessionId = "session-alpha",
    )

    OpenCrayAgentRuntimeService.startScheduledTask(context, command)

    val startedRequest = recordingStarter.startedRequests.single()
    assertTrue(startedRequest.foreground)
    assertEquals(ACTION_RUN_SCHEDULED_TASK, startedRequest.request.action)
    assertEquals("schedule-alpha", startedRequest.request.extras[EXTRA_SCHEDULE_ID])
    assertEquals("schedule-run-alpha", startedRequest.request.extras[EXTRA_SCHEDULE_RUN_ID])
    assertEquals(1234L, startedRequest.request.extras[EXTRA_TRIGGERED_AT_EPOCH_MS])
    assertEquals(
      ScheduledTaskTriggerReasons.WORK_MANAGER,
      startedRequest.request.extras[EXTRA_TRIGGER_REASON],
    )
    assertEquals("session-alpha", startedRequest.request.extras[EXTRA_TARGET_SESSION_ID])
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun repairSchedulesOnlyRequestsServiceWakeWithoutCreatingRuntimeHost() {
    val context = MinimalContext()

    val started = OpenCrayAgentRuntimeService.repairSchedules(
      context = context,
      repairReason = ScheduledTaskRepairReasons.WORK_MANAGER,
    )

    val startedRequest = recordingStarter.startedRequests.single()
    assertTrue(started)
    assertTrue(startedRequest.foreground)
    assertEquals(ACTION_REPAIR_SCHEDULES, startedRequest.request.action)
    assertEquals(
      ScheduledTaskRepairReasons.WORK_MANAGER,
      startedRequest.request.extras[EXTRA_REPAIR_REASON],
    )
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun resumeInterruptedRunsOnlyRequestsServiceWakeWithoutCreatingRuntimeHost() {
    val context = MinimalContext()

    val started = OpenCrayAgentRuntimeService.resumeInterruptedRuns(
      context = context,
      repairReason = ScheduledTaskRepairReasons.WORK_MANAGER,
    )

    val startedRequest = recordingStarter.startedRequests.single()
    assertTrue(started)
    assertTrue(startedRequest.foreground)
    assertEquals(ACTION_RESUME_INTERRUPTED_RUNS, startedRequest.request.action)
    assertEquals(
      ScheduledTaskRepairReasons.WORK_MANAGER,
      startedRequest.request.extras[EXTRA_REPAIR_REASON],
    )
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun repairSchedulesReturnsFalseWhenServiceWakeFailsWithoutCreatingRuntimeHost() {
    val context = MinimalContext()
    recordingStarter.throwOnStart = true

    val started = OpenCrayAgentRuntimeService.repairSchedules(
      context = context,
      repairReason = ScheduledTaskRepairReasons.WORK_MANAGER,
    )

    assertFalse(started)
    assertEquals(1, recordingStarter.startAttempts.size)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  private fun clearRuntimeSingletons() {
    OpenCrayRuntimeServiceHostRegistry.clearForTest()
    InProcessOpenCrayRuntimeOwnerRegistry.clearForTest()
  }

  private class MinimalContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this

    override fun getPackageName(): String = "org.opencray.app"
  }

  private class RecordingRuntimeServiceStarter : RuntimeServiceStarter {
    var throwOnStart: Boolean = false
    val startAttempts = mutableListOf<RecordedStart>()
    val startedRequests = mutableListOf<RecordedStart>()

    override fun start(
      context: Context,
      request: RuntimeServiceStartRequest,
      foreground: Boolean,
    ): Boolean {
      val attempt = RecordedStart(
        contextPackageName = context.packageName,
        request = request,
        foreground = foreground,
      )
      startAttempts += attempt
      if (throwOnStart) {
        return false
      }
      startedRequests += attempt
      return true
    }
  }

  private data class RecordedStart(
    val contextPackageName: String,
    val request: RuntimeServiceStartRequest,
    val foreground: Boolean,
  )
}
