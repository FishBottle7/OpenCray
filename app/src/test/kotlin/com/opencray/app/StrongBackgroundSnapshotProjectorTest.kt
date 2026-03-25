package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrongBackgroundSnapshotProjectorTest {
  @Test
  fun projectMarksStrongBackgroundCompleteWhenAllRequirementsConfigured() {
    val snapshot = StrongBackgroundSnapshotProjector.project(
      StrongBackgroundCapabilityState(
        notificationPermissionRequired = true,
        notificationPermissionGranted = true,
        notificationsEnabled = true,
        notificationSettingsSupported = true,
        exactAlarmAccessRequired = true,
        exactAlarmAccessGranted = true,
        exactAlarmSettingsSupported = true,
        batteryOptimizationSupported = true,
        batteryOptimizationExempt = true,
        batteryOptimizationSettingsSupported = true,
        directBatteryOptimizationRequestSupported = true,
      ),
    )

    assertEquals(StrongBackgroundTierIds.STRONG_BACKGROUND, snapshot["tierId"])
    assertEquals(true, snapshot["setupComplete"])
    assertTrue((snapshot["recommendedActionIds"] as List<*>).isEmpty())
  }

  @Test
  fun projectRecommendsMissingNotificationExactAlarmAndBatteryActions() {
    val snapshot = StrongBackgroundSnapshotProjector.project(
      StrongBackgroundCapabilityState(
        notificationPermissionRequired = true,
        notificationPermissionGranted = false,
        notificationsEnabled = false,
        notificationSettingsSupported = true,
        exactAlarmAccessRequired = true,
        exactAlarmAccessGranted = false,
        exactAlarmSettingsSupported = true,
        batteryOptimizationSupported = true,
        batteryOptimizationExempt = false,
        batteryOptimizationSettingsSupported = true,
        directBatteryOptimizationRequestSupported = true,
      ),
    )

    assertEquals(StrongBackgroundTierIds.BASELINE, snapshot["tierId"])
    assertEquals(false, snapshot["setupComplete"])
    assertEquals(
      listOf(
        StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
        StrongBackgroundActionIds.OPEN_EXACT_ALARM_SETTINGS,
        StrongBackgroundActionIds.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
      ),
      snapshot["recommendedActionIds"],
    )
  }

  @Test
  fun projectFallsBackToBatterySettingsWhenDirectBatteryRequestIsUnavailable() {
    val snapshot = StrongBackgroundSnapshotProjector.project(
      StrongBackgroundCapabilityState(
        notificationPermissionRequired = false,
        notificationPermissionGranted = true,
        notificationsEnabled = true,
        notificationSettingsSupported = true,
        exactAlarmAccessRequired = false,
        exactAlarmAccessGranted = true,
        exactAlarmSettingsSupported = false,
        batteryOptimizationSupported = true,
        batteryOptimizationExempt = false,
        batteryOptimizationSettingsSupported = true,
        directBatteryOptimizationRequestSupported = false,
      ),
    )

    assertEquals(
      listOf(StrongBackgroundActionIds.OPEN_BATTERY_OPTIMIZATION_SETTINGS),
      snapshot["recommendedActionIds"],
    )
    @Suppress("UNCHECKED_CAST")
    val actions = snapshot["actions"] as List<Map<String, Any?>>
    val batterySettingsAction = actions.first { action ->
      action["id"] == StrongBackgroundActionIds.OPEN_BATTERY_OPTIMIZATION_SETTINGS
    }
    val directRequestAction = actions.first { action ->
      action["id"] == StrongBackgroundActionIds.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    }
    assertEquals(true, batterySettingsAction["recommended"])
    assertFalse(directRequestAction["available"] as Boolean)
  }
}
