package com.opencray.app

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledWorkSchedulerRoutingTest {
  @Test
  fun secondaryProcessesRouteScheduledWorkThroughMainProcessProxy() {
    assertEquals(
      WorkManagerClientRoute.MAIN_PROCESS,
      workManagerClientRoute(
        packageName = "org.opencray.app",
        processName = "org.opencray.app",
      ),
    )
    listOf(
      "org.opencray.app:runtime",
      "org.opencray.app:runtime_controller",
      "org.opencray.app:service_opencraypython",
    ).forEach { processName ->
      assertEquals(
        WorkManagerClientRoute.MAIN_PROCESS_PROXY,
        workManagerClientRoute(
          packageName = "org.opencray.app",
          processName = processName,
        ),
      )
    }
    assertEquals(
      WorkManagerClientRoute.MAIN_PROCESS_PROXY,
      workManagerClientRoute(
        packageName = "org.opencray.app",
        processName = null,
      ),
    )
  }

  @Test
  fun proxyConvertsSchedulerOperationsToStructuredCommands() {
    val commands = mutableListOf<ScheduledWorkCommand>()
    val scheduler = MainProcessScheduledWorkSchedulerProxy(commands::add)

    scheduler.scheduleWake("schedule-1", 1_500L)
    scheduler.cancel("schedule-2")
    scheduler.enqueueRepair("owner_lease_expired", initialDelayMs = 250L)
    scheduler.enqueueRepair("retry", initialDelayMs = -1L)
    scheduler.ensurePeriodicRepair()

    assertEquals(
      listOf(
        ScheduledWorkCommand.ScheduleWake("schedule-1", 1_500L),
        ScheduledWorkCommand.CancelWake("schedule-2"),
        ScheduledWorkCommand.EnqueueRepair("owner_lease_expired", 250L),
        ScheduledWorkCommand.EnqueueRepair("retry", 0L),
        ScheduledWorkCommand.EnsurePeriodicRepair,
      ),
      commands,
    )
  }

  @Test
  fun scheduledWorkCommandIntentCodecRoundTripsEveryCommand() {
    val commands = listOf(
      ScheduledWorkCommand.ScheduleWake("schedule-1", 1_500L),
      ScheduledWorkCommand.CancelWake("schedule-2"),
      ScheduledWorkCommand.EnqueueRepair("managed_process_reconnect", 250L),
      ScheduledWorkCommand.EnsurePeriodicRepair,
    )

    commands.forEach { command ->
      val intent = encodeScheduledWorkCommand(RecordingIntent(), command)

      assertEquals(command, parseScheduledWorkCommand(intent))
    }
  }

  @Test
  fun scheduledWorkCommandParserRejectsMalformedCommands() {
    assertNull(
      parseScheduledWorkCommand(
        action = "unknown",
        commandKind = "schedule_wake",
        scheduleId = "schedule-1",
        triggerAtEpochMs = 1L,
        repairReason = null,
        initialDelayMs = null,
      ),
    )
    assertNull(
      parseScheduledWorkCommand(
        action = ACTION_SCHEDULED_WORK_COMMAND,
        commandKind = "schedule_wake",
        scheduleId = " ",
        triggerAtEpochMs = 1L,
        repairReason = null,
        initialDelayMs = null,
      ),
    )
    assertNull(
      parseScheduledWorkCommand(
        action = ACTION_SCHEDULED_WORK_COMMAND,
        commandKind = "enqueue_repair",
        scheduleId = null,
        triggerAtEpochMs = null,
        repairReason = "repair",
        initialDelayMs = -1L,
      ),
    )
    assertNull(
      parseScheduledWorkCommand(
        action = ACTION_SCHEDULED_WORK_COMMAND,
        commandKind = "unknown",
        scheduleId = null,
        triggerAtEpochMs = null,
        repairReason = null,
        initialDelayMs = null,
      ),
    )
  }

  @Test
  fun commandDispatcherDelegatesToMainProcessScheduler() {
    val scheduler = RecordingScheduledWorkScheduler()

    listOf(
      ScheduledWorkCommand.ScheduleWake("schedule-1", 1_500L),
      ScheduledWorkCommand.CancelWake("schedule-2"),
      ScheduledWorkCommand.EnqueueRepair("owner_lease_expired", 250L),
      ScheduledWorkCommand.EnsurePeriodicRepair,
    ).forEach { command ->
      dispatchScheduledWorkCommand(command, scheduler)
    }

    assertEquals(listOf("schedule-1" to 1_500L), scheduler.scheduledWakes)
    assertEquals(listOf("schedule-2"), scheduler.cancelledScheduleIds)
    assertEquals(listOf("owner_lease_expired" to 250L), scheduler.enqueuedRepairs)
    assertEquals(1, scheduler.periodicRepairRequests)
  }

  private class RecordingIntent : Intent() {
    private val extras: MutableMap<String, Any?> = linkedMapOf()
    private var storedAction: String? = null

    override fun setAction(action: String?): Intent {
      storedAction = action
      return this
    }

    override fun getAction(): String? = storedAction

    override fun putExtra(
      name: String?,
      value: String?,
    ): Intent {
      if (name != null) {
        extras[name] = value
      }
      return this
    }

    override fun putExtra(
      name: String?,
      value: Long,
    ): Intent {
      if (name != null) {
        extras[name] = value
      }
      return this
    }

    override fun getStringExtra(name: String?): String? =
      name?.let(extras::get) as? String

    override fun getLongExtra(
      name: String?,
      defaultValue: Long,
    ): Long = (name?.let(extras::get) as? Long) ?: defaultValue
  }

  private class RecordingScheduledWorkScheduler : ScheduledWorkScheduler {
    val scheduledWakes = mutableListOf<Pair<String, Long>>()
    val cancelledScheduleIds = mutableListOf<String>()
    val enqueuedRepairs = mutableListOf<Pair<String, Long>>()
    var periodicRepairRequests: Int = 0

    override fun scheduleWake(
      scheduleId: String,
      triggerAtEpochMs: Long,
    ) {
      scheduledWakes += scheduleId to triggerAtEpochMs
    }

    override fun cancel(scheduleId: String) {
      cancelledScheduleIds += scheduleId
    }

    override fun enqueueRepair(
      reason: String,
      initialDelayMs: Long,
    ) {
      enqueuedRepairs += reason to initialDelayMs
    }

    override fun ensurePeriodicRepair() {
      periodicRepairRequests += 1
    }
  }
}
