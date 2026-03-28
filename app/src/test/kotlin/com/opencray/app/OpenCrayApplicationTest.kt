package com.opencray.app

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCrayApplicationTest {
  @Test
  fun bootstrapOpenCrayApplicationResyncsSchedulesQueuesRepairAndStartsRuntime() {
    val application = Application()
    val steps = mutableListOf<String>()

    bootstrapOpenCrayApplication(
      application = application,
      registerVisibility = {
        steps += "register_visibility"
      },
      seedBundledSkills = {
        steps += "seed_skills"
      },
      resyncEnabledSchedules = {
        steps += "resync_schedules"
      },
      enqueueRepair = { _, reason ->
        steps += "enqueue_repair:$reason"
      },
      ensureRuntime = {
        steps += "ensure_runtime"
      },
    )

    assertEquals(
      listOf(
        "register_visibility",
        "seed_skills",
        "resync_schedules",
        "enqueue_repair:${ScheduledTaskRepairReasons.APP_START}",
        "ensure_runtime",
      ),
      steps,
    )
  }

  @Test
  fun bootstrapOpenCrayApplicationStillQueuesRepairWhenResyncFails() {
    val application = Application()
    val steps = mutableListOf<String>()

    bootstrapOpenCrayApplication(
      application = application,
      registerVisibility = { steps += "register_visibility" },
      seedBundledSkills = { steps += "seed_skills" },
      resyncEnabledSchedules = {
        steps += "resync_schedules"
        error("boom")
      },
      enqueueRepair = { _, reason ->
        steps += "enqueue_repair:$reason"
      },
      ensureRuntime = {
        steps += "ensure_runtime"
      },
    )

    assertEquals(
      listOf(
        "register_visibility",
        "seed_skills",
        "resync_schedules",
        "enqueue_repair:${ScheduledTaskRepairReasons.APP_START}",
        "ensure_runtime",
      ),
      steps,
    )
  }
}
