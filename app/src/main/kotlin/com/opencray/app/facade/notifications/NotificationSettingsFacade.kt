package com.opencray.app.facade.notifications

import android.content.Context
import com.opencray.app.InMemoryRuntimeNotificationSettingsKeyValueStore
import com.opencray.app.RuntimeNotificationDeliveryMode
import com.opencray.app.RuntimeNotificationSettingsState
import com.opencray.app.RuntimeNotificationSettingsStore

data class NotificationSettingsSnapshot(
  val masterEnabled: Boolean,
  val defaultDeliveryMode: RuntimeNotificationDeliveryMode,
  val quietHoursEnabled: Boolean,
  val quietHoursStartMinutes: Int,
  val quietHoursEndMinutes: Int,
  val approvalRequestsEnabled: Boolean,
  val approvalReminderEnabled: Boolean,
  val taskFinishedEnabled: Boolean,
  val taskFailedEnabled: Boolean,
  val scheduledWakeEnabled: Boolean,
  val backgroundTaskPausedEnabled: Boolean,
  val serviceRecoveredEnabled: Boolean,
)

data class SaveNotificationSettingsRequest(
  val masterEnabled: Boolean,
  val defaultDeliveryModeId: String,
  val quietHoursEnabled: Boolean,
  val quietHoursStartMinutes: Int,
  val quietHoursEndMinutes: Int,
  val approvalRequestsEnabled: Boolean,
  val approvalReminderEnabled: Boolean,
  val taskFinishedEnabled: Boolean,
  val taskFailedEnabled: Boolean,
  val scheduledWakeEnabled: Boolean,
  val backgroundTaskPausedEnabled: Boolean,
  val serviceRecoveredEnabled: Boolean,
)

interface NotificationSettingsFacade {
  fun load(): NotificationSettingsSnapshot

  fun save(request: SaveNotificationSettingsRequest): NotificationSettingsSnapshot
}

object EmptyNotificationSettingsFacade : NotificationSettingsFacade {
  private var state: NotificationSettingsSnapshot = RuntimeNotificationSettingsState().toSnapshot()

  override fun load(): NotificationSettingsSnapshot = state

  override fun save(request: SaveNotificationSettingsRequest): NotificationSettingsSnapshot {
    state = request.toState().toSnapshot()
    return state
  }
}

internal class LocalNotificationSettingsFacade(
  private val store: RuntimeNotificationSettingsStore,
) : NotificationSettingsFacade {
  override fun load(): NotificationSettingsSnapshot = store.load().toSnapshot()

  override fun save(request: SaveNotificationSettingsRequest): NotificationSettingsSnapshot {
    val state = request.toState()
    store.save(state)
    return state.toSnapshot()
  }

  companion object {
    fun fromContext(context: Context): LocalNotificationSettingsFacade =
      LocalNotificationSettingsFacade(
        store = RuntimeNotificationSettingsStore.fromContext(context.applicationContext),
      )

    fun createForTest(
      store: RuntimeNotificationSettingsStore = RuntimeNotificationSettingsStore(
        InMemoryRuntimeNotificationSettingsKeyValueStore(),
      ),
    ): LocalNotificationSettingsFacade = LocalNotificationSettingsFacade(store = store)
  }
}

private fun SaveNotificationSettingsRequest.toState(): RuntimeNotificationSettingsState =
  RuntimeNotificationSettingsState(
    masterEnabled = masterEnabled,
    defaultDeliveryMode = RuntimeNotificationDeliveryMode.fromWireValue(defaultDeliveryModeId),
    quietHoursEnabled = quietHoursEnabled,
    quietHoursStartMinutes = quietHoursStartMinutes,
    quietHoursEndMinutes = quietHoursEndMinutes,
    approvalRequestsEnabled = approvalRequestsEnabled,
    approvalReminderEnabled = approvalReminderEnabled,
    taskFinishedEnabled = taskFinishedEnabled,
    taskFailedEnabled = taskFailedEnabled,
    scheduledWakeEnabled = scheduledWakeEnabled,
    backgroundTaskPausedEnabled = backgroundTaskPausedEnabled,
    serviceRecoveredEnabled = serviceRecoveredEnabled,
  ).sanitized()

private fun RuntimeNotificationSettingsState.toSnapshot(): NotificationSettingsSnapshot =
  NotificationSettingsSnapshot(
    masterEnabled = masterEnabled,
    defaultDeliveryMode = defaultDeliveryMode,
    quietHoursEnabled = quietHoursEnabled,
    quietHoursStartMinutes = quietHoursStartMinutes,
    quietHoursEndMinutes = quietHoursEndMinutes,
    approvalRequestsEnabled = approvalRequestsEnabled,
    approvalReminderEnabled = approvalReminderEnabled,
    taskFinishedEnabled = taskFinishedEnabled,
    taskFailedEnabled = taskFailedEnabled,
    scheduledWakeEnabled = scheduledWakeEnabled,
    backgroundTaskPausedEnabled = backgroundTaskPausedEnabled,
    serviceRecoveredEnabled = serviceRecoveredEnabled,
  )
