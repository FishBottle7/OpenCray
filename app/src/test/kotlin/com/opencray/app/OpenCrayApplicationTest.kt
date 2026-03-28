package com.opencray.app

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCrayApplicationTest {
  @Test
  fun shouldBootstrapOpenCrayApplicationReturnsTrueForMainProcess() {
    assertEquals(
      true,
      shouldBootstrapOpenCrayApplication(
        packageName = "org.opencray.app",
        processName = "org.opencray.app",
      ),
    )
  }

  @Test
  fun shouldBootstrapOpenCrayApplicationReturnsFalseForSecondaryProcess() {
    assertEquals(
      false,
      shouldBootstrapOpenCrayApplication(
        packageName = "org.opencray.app",
        processName = "org.opencray.app:service_opencraypython",
      ),
    )
  }

  @Test
  fun shouldBootstrapOpenCrayApplicationDefaultsToTrueWhenProcessNameMissing() {
    assertEquals(
      true,
      shouldBootstrapOpenCrayApplication(
        packageName = "org.opencray.app",
        processName = null,
      ),
    )
  }

  @Test
  fun bootstrapOpenCrayApplicationResyncsSchedulesAndQueuesRepairWithoutStartingRuntime() {
    val application = Application()
    val steps = mutableListOf<String>()

    bootstrapOpenCrayApplication(
      application = application,
      registerVisibility = {
        steps += "register_visibility"
      },
      initializeRuntimeDocumentSupport = {
        steps += "initialize_document_support"
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
    )

    assertEquals(
      listOf(
        "register_visibility",
        "initialize_document_support",
        "seed_skills",
        "resync_schedules",
        "enqueue_repair:${ScheduledTaskRepairReasons.APP_START}",
      ),
      steps,
    )
  }

  @Test
  fun bootstrapOpenCrayApplicationStillQueuesRepairWhenResyncFailsWithoutStartingRuntime() {
    val application = Application()
    val steps = mutableListOf<String>()

    bootstrapOpenCrayApplication(
      application = application,
      registerVisibility = { steps += "register_visibility" },
      initializeRuntimeDocumentSupport = { steps += "initialize_document_support" },
      seedBundledSkills = { steps += "seed_skills" },
      resyncEnabledSchedules = {
        steps += "resync_schedules"
        error("boom")
      },
      enqueueRepair = { _, reason ->
        steps += "enqueue_repair:$reason"
      },
    )

    assertEquals(
      listOf(
        "register_visibility",
        "initialize_document_support",
        "seed_skills",
        "resync_schedules",
        "enqueue_repair:${ScheduledTaskRepairReasons.APP_START}",
      ),
      steps,
    )
  }
}
