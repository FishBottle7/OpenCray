package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeNotificationSettingsStoreTest {
  @Test
  fun persistsNotificationSettingsRoundTrip() {
    val store = RuntimeNotificationSettingsStore(
      InMemoryRuntimeNotificationSettingsKeyValueStore(),
    )
    val expected = RuntimeNotificationSettingsState(
      masterEnabled = false,
      defaultDeliveryMode = RuntimeNotificationDeliveryMode.ALL,
      quietHoursEnabled = true,
      quietHoursStartMinutes = 22 * 60,
      quietHoursEndMinutes = 7 * 60,
      approvalRequestsEnabled = true,
      approvalReminderEnabled = false,
      taskFinishedEnabled = true,
      taskFailedEnabled = true,
      scheduledWakeEnabled = true,
      backgroundTaskPausedEnabled = false,
      serviceRecoveredEnabled = true,
    )

    store.save(expected)

    assertEquals(expected.sanitized(), store.load())
  }

  @Test
  fun quietHoursHandlesCrossMidnightRanges() {
    val state = RuntimeNotificationSettingsState(
      quietHoursEnabled = true,
      quietHoursStartMinutes = 23 * 60,
      quietHoursEndMinutes = 8 * 60,
    )

    assertTrue(state.isQuietHoursActiveAt(23 * 60))
    assertTrue(state.isQuietHoursActiveAt((7 * 60) + 59))
    assertFalse(state.isQuietHoursActiveAt(12 * 60))
  }

  @Test
  fun userPolicyKeepsCriticalNotificationsDuringQuietHours() {
    val state = RuntimeNotificationSettingsState(
      masterEnabled = true,
      defaultDeliveryMode = RuntimeNotificationDeliveryMode.ALL,
      quietHoursEnabled = true,
      quietHoursStartMinutes = 23 * 60,
      quietHoursEndMinutes = 8 * 60,
      taskFinishedEnabled = true,
      taskFailedEnabled = true,
    )

    assertTrue(
      RuntimeNotificationUserPolicy.allows(
        settings = state,
        event = RuntimeNotificationUserEvent.TASK_FAILED,
        minutesOfDay = 1 * 60,
      ),
    )
    assertFalse(
      RuntimeNotificationUserPolicy.allows(
        settings = state,
        event = RuntimeNotificationUserEvent.TASK_FINISHED,
        minutesOfDay = 1 * 60,
      ),
    )
    assertTrue(
      RuntimeNotificationUserPolicy.allows(
        settings = state,
        event = RuntimeNotificationUserEvent.TASK_FINISHED,
        minutesOfDay = 12 * 60,
      ),
    )
  }

  @Test
  fun userPolicyHonorsMasterAndChannelToggles() {
    val masterDisabled = RuntimeNotificationSettingsState(
      masterEnabled = false,
      approvalRequestsEnabled = true,
    )
    val approvalChannelDisabled = RuntimeNotificationSettingsState(
      masterEnabled = true,
      approvalRequestsEnabled = false,
    )

    assertFalse(
      RuntimeNotificationUserPolicy.allows(
        settings = masterDisabled,
        event = RuntimeNotificationUserEvent.APPROVAL_REQUEST,
        minutesOfDay = 12 * 60,
      ),
    )
    assertFalse(
      RuntimeNotificationUserPolicy.allows(
        settings = approvalChannelDisabled,
        event = RuntimeNotificationUserEvent.APPROVAL_REQUEST,
        minutesOfDay = 12 * 60,
      ),
    )
  }
}
