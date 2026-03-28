package com.opencray.app

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledTaskWorkManagerTest {
  @Test
  fun scheduledTaskRepairReasonForActionMapsKnownBroadcasts() {
    assertEquals(
      ScheduledTaskRepairReasons.BOOT_COMPLETED,
      scheduledTaskRepairReasonForAction(Intent.ACTION_BOOT_COMPLETED),
    )
    assertEquals(
      ScheduledTaskRepairReasons.PACKAGE_REPLACED,
      scheduledTaskRepairReasonForAction(Intent.ACTION_MY_PACKAGE_REPLACED),
    )
    assertEquals(null, scheduledTaskRepairReasonForAction("custom.action.UNKNOWN"))
    assertEquals(null, scheduledTaskRepairReasonForAction(null))
  }
}
