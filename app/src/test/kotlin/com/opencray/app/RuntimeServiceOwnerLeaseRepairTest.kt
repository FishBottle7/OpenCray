package com.opencray.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeServiceOwnerLeaseRepairTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun corruptLeaseFileFailsClosedWithoutSchedulingRepairOrCrashing() {
    val root = temporaryFolder.newFolder("runtime-owner-lease-repair-corrupt")
    val store = FileBackedRuntimeServiceOwnerLeaseStore.fromRootDirectory(root)
    val fileName =
      "runtime-service-owner-lease-${RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue}.json"
    File(root, fileName).writeText("{\"target\":\"detached_background\",\"ph")
    val workScheduler = RecordingScheduledWorkScheduler()

    assertFalse(
      scheduleRuntimeOwnerLeaseExpiryRepair(
        target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        nowEpochMs = 10_000L,
        ownerLeaseStore = store,
        workScheduler = workScheduler,
      ),
    )
    assertTrue(workScheduler.repairEnqueues.isEmpty())
    assertNull(
      nextRuntimeOwnerLeaseExpiryRepairDelayMs(
        targets = RuntimeServiceTarget.entries,
        ownerLeaseStore = store,
        nowEpochMs = 10_000L,
      ),
    )
    assertTrue(workScheduler.repairEnqueues.isEmpty())
    assertTrue(
      dueRuntimeOwnerLeaseExpiryRepairTargets(
        targets = RuntimeServiceTarget.entries,
        ownerLeaseStore = store,
        nowEpochMs = 10_000L,
      ).isEmpty(),
    )
    assertEquals(
      "{\"target\":\"detached_background\",\"ph",
      File(root, fileName).readText(),
    )
  }

  private class RecordingScheduledWorkScheduler : ScheduledWorkScheduler {
    val repairEnqueues = mutableListOf<Pair<String, Long>>()
    val wakeSchedules = mutableListOf<Pair<String, Long>>()

    override fun scheduleWake(
      scheduleId: String,
      triggerAtEpochMs: Long,
    ) {
      wakeSchedules += scheduleId to triggerAtEpochMs
    }

    override fun cancel(scheduleId: String) = Unit

    override fun enqueueRepair(
      reason: String,
      initialDelayMs: Long,
    ) {
      repairEnqueues += reason to initialDelayMs
    }

    override fun ensurePeriodicRepair() = Unit
  }
}
