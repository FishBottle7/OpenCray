package com.opencray.app

import android.app.Application
import org.junit.Assert.assertSame
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
  fun shouldBootstrapOpenCrayApplicationReturnsFalseForRuntimeProcess() {
    assertEquals(
      false,
      shouldBootstrapOpenCrayApplication(
        packageName = "org.opencray.app",
        processName = "org.opencray.app:runtime",
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
      ensurePeriodicRepair = {
        steps += "ensure_periodic_repair"
      },
    )

    assertEquals(
      listOf(
        "register_visibility",
        "initialize_document_support",
        "seed_skills",
        "resync_schedules",
        "enqueue_repair:${ScheduledTaskRepairReasons.APP_START}",
        "ensure_periodic_repair",
      ),
      steps,
    )
  }

  @Test
  fun bootstrapOpenCrayRuntimeProcessSupportInitializesDocumentsAndSeedsSkillsOnly() {
    val application = Application()
    val steps = mutableListOf<String>()

    bootstrapOpenCrayRuntimeProcessSupport(
      context = application,
      initializeRuntimeDocumentSupport = {
        steps += "initialize_document_support"
      },
      seedBundledSkills = {
        steps += "seed_skills"
      },
    )

    assertEquals(
      listOf(
        "initialize_document_support",
        "seed_skills",
      ),
      steps,
    )
  }

  @Test
  fun bootstrapOpenCrayRuntimeServiceProcessSupportAlsoRegistersNotificationChannels() {
    val application = Application()
    val steps = mutableListOf<String>()

    bootstrapOpenCrayRuntimeServiceProcessSupport(
      context = application,
      runtimeProcessSupportBootstrap = {
        steps += "runtime_process_support"
      },
      notificationChannelRegistrar = {
        steps += "register_notification_channels"
      },
    )

    assertEquals(
      listOf(
        "runtime_process_support",
        "register_notification_channels",
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
      ensurePeriodicRepair = {
        steps += "ensure_periodic_repair"
      },
    )

    assertEquals(
      listOf(
        "register_visibility",
        "initialize_document_support",
        "seed_skills",
        "resync_schedules",
        "enqueue_repair:${ScheduledTaskRepairReasons.APP_START}",
        "ensure_periodic_repair",
      ),
      steps,
    )
  }

  @Test
  fun openCrayApplicationExposesStableRuntimeServiceEnvironment() {
    val application = OpenCrayApplication()

    val first = application.openCrayRuntimeServiceEnvironment
    val second = application.openCrayRuntimeServiceEnvironment

    assertSame(first, second)
    assertSame(first.runtimeServiceAccessGateway, second.runtimeServiceAccessGateway)
    assertSame(first.executionControllerResolver, second.executionControllerResolver)
  }
}
