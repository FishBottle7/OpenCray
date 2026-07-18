package com.opencray.app

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeNotificationSettingsStoreTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun persistsNotificationSettingsRoundTrip() {
    val store = RuntimeNotificationSettingsStore(
      DirectoryDurableTextStorage(
        temporaryFolder.newFolder("runtime-notification-settings-round-trip"),
      ),
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
  fun fileBackedStoreRoundTripsAcrossInstances() {
    val directory = temporaryFolder.newFolder("runtime-notification-settings-cross-instance")
    val firstStore = RuntimeNotificationSettingsStore(
      DirectoryDurableTextStorage(directory),
    )
    val secondStore = RuntimeNotificationSettingsStore(
      DirectoryDurableTextStorage(directory),
    )
    val expected = RuntimeNotificationSettingsState(
      masterEnabled = true,
      defaultDeliveryMode = RuntimeNotificationDeliveryMode.CRITICAL,
      quietHoursEnabled = false,
      quietHoursStartMinutes = -30,
      quietHoursEndMinutes = (25 * 60) + 15,
      approvalRequestsEnabled = false,
      approvalReminderEnabled = true,
      taskFinishedEnabled = false,
      taskFailedEnabled = true,
      scheduledWakeEnabled = false,
      backgroundTaskPausedEnabled = true,
      serviceRecoveredEnabled = false,
    )

    firstStore.save(expected)

    assertEquals(expected.sanitized(), secondStore.load())
  }

  @Test
  fun fileBackedStoreClearRemovesPersistedSnapshot() {
    val directory = temporaryFolder.newFolder("runtime-notification-settings-clear")
    val store = RuntimeNotificationSettingsStore(
      DirectoryDurableTextStorage(directory),
    )
    store.save(
      RuntimeNotificationSettingsState(
        masterEnabled = false,
        approvalRequestsEnabled = false,
      ),
    )

    store.clear()

    assertEquals(RuntimeNotificationSettingsState(), store.load())
  }

  @Test
  fun saveAndClearUseDurableUpdatePath() {
    val storage = StaleReadDurableTextStorage()
    val store = RuntimeNotificationSettingsStore(storage)

    store.save(RuntimeNotificationSettingsState(masterEnabled = true))
    val staleSnapshot = storage.currentText
    store.save(RuntimeNotificationSettingsState(taskFinishedEnabled = false))
    storage.returnStaleTextOnNextRead(staleSnapshot)
    val expected = RuntimeNotificationSettingsState(
      masterEnabled = false,
      quietHoursStartMinutes = -10,
      quietHoursEndMinutes = (25 * 60) + 5,
      taskFinishedEnabled = true,
    )

    store.save(expected)

    assertEquals(3, storage.updateTextCallCount)
    assertTrue(storage.hasPendingStaleRead)
    storage.clearPendingStaleRead()
    assertEquals(expected.sanitized(), store.load())

    store.clear()

    assertEquals(4, storage.updateTextCallCount)
    assertEquals(RuntimeNotificationSettingsState(), store.load())
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

  @Test
  fun partialGatewayPayloadUsesTheRuntimeNotificationDefaults() {
    val defaults = RuntimeNotificationSettingsState()

    val request = mapOf<String, Any?>(
      "masterEnabled" to false,
    ).toSaveNotificationSettingsRequest()

    assertFalse(request.masterEnabled)
    assertEquals(defaults.defaultDeliveryMode.wireValue, request.defaultDeliveryModeId)
    assertEquals(defaults.taskFinishedEnabled, request.taskFinishedEnabled)
    assertEquals(defaults.scheduledWakeEnabled, request.scheduledWakeEnabled)
    assertEquals(defaults.serviceRecoveredEnabled, request.serviceRecoveredEnabled)
  }

  private class StaleReadDurableTextStorage : DurableTextStorage {
    private var text: String? = null
    private var staleReadText: String? = null
    var hasPendingStaleRead: Boolean = false
      private set
    var updateTextCallCount: Int = 0
      private set

    val currentText: String?
      get() = text

    fun returnStaleTextOnNextRead(staleText: String?) {
      staleReadText = staleText
      hasPendingStaleRead = true
    }

    fun clearPendingStaleRead() {
      staleReadText = null
      hasPendingStaleRead = false
    }

    override fun readText(name: String): String? {
      if (!hasPendingStaleRead) {
        return text
      }
      hasPendingStaleRead = false
      return staleReadText
    }

    override fun writeText(name: String, text: String) {
      this.text = text
    }

    override fun delete(name: String): Boolean {
      val hadText = text != null
      text = null
      return hadText
    }

    override fun <T> updateText(
      name: String,
      update: (String?) -> DurableTextUpdate<T>,
    ): T {
      updateTextCallCount += 1
      val updated = update(text)
      if (updated.write) {
        text = updated.text
      }
      return updated.result
    }
  }
}
