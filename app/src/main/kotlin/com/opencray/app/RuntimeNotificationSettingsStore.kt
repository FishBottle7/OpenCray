package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.Serializable

private const val RUNTIME_NOTIFICATION_SETTINGS_FILE_NAME =
  "runtime-notification-settings.json"

enum class RuntimeNotificationDeliveryMode(
  val wireValue: String,
) {
  CRITICAL("critical"),
  ALL("all"),
  ;

  companion object {
    fun fromWireValue(rawValue: String?): RuntimeNotificationDeliveryMode =
      entries.firstOrNull { mode -> mode.wireValue == rawValue?.trim() } ?: CRITICAL
  }
}

internal data class RuntimeNotificationSettingsState(
  val masterEnabled: Boolean = true,
  val defaultDeliveryMode: RuntimeNotificationDeliveryMode =
    RuntimeNotificationDeliveryMode.ALL,
  val quietHoursEnabled: Boolean = true,
  val quietHoursStartMinutes: Int = DEFAULT_QUIET_HOURS_START_MINUTES,
  val quietHoursEndMinutes: Int = DEFAULT_QUIET_HOURS_END_MINUTES,
  val approvalRequestsEnabled: Boolean = true,
  val approvalReminderEnabled: Boolean = true,
  val taskFinishedEnabled: Boolean = true,
  val taskFailedEnabled: Boolean = true,
  val scheduledWakeEnabled: Boolean = true,
  val backgroundTaskPausedEnabled: Boolean = true,
  val serviceRecoveredEnabled: Boolean = true,
) {
  fun sanitized(): RuntimeNotificationSettingsState = copy(
    quietHoursStartMinutes = quietHoursStartMinutes.normalizedMinutesOfDay(),
    quietHoursEndMinutes = quietHoursEndMinutes.normalizedMinutesOfDay(),
  )

  fun isQuietHoursActiveAt(minutesOfDay: Int): Boolean {
    if (!quietHoursEnabled) {
      return false
    }
    val normalizedMinutes = minutesOfDay.normalizedMinutesOfDay()
    val start = quietHoursStartMinutes.normalizedMinutesOfDay()
    val end = quietHoursEndMinutes.normalizedMinutesOfDay()
    if (start == end) {
      return true
    }
    return if (start < end) {
      normalizedMinutes in start until end
    } else {
      normalizedMinutes >= start || normalizedMinutes < end
    }
  }

  companion object {
    const val DEFAULT_QUIET_HOURS_START_MINUTES: Int = 23 * 60
    const val DEFAULT_QUIET_HOURS_END_MINUTES: Int = 8 * 60
  }
}

internal class RuntimeNotificationSettingsStore(
  private val storage: DurableTextStorage,
) {
  private val lock = Any()

  fun load(
    defaults: RuntimeNotificationSettingsState = RuntimeNotificationSettingsState(),
  ): RuntimeNotificationSettingsState = synchronized(lock) {
    loadRecord()?.toState(defaults = defaults) ?: defaults.sanitized()
  }

  fun save(state: RuntimeNotificationSettingsState) {
    synchronized(lock) {
      val sanitized = state.sanitized()
      storage.writeText(
        RUNTIME_NOTIFICATION_SETTINGS_FILE_NAME,
        PersistenceJson.instance.encodeToString(
          serializer = PersistedRuntimeNotificationSettingsRecord.serializer(),
          value = PersistedRuntimeNotificationSettingsRecord.fromState(sanitized),
        ),
      )
    }
  }

  fun clear() {
    synchronized(lock) {
      storage.delete(RUNTIME_NOTIFICATION_SETTINGS_FILE_NAME)
    }
  }

  private fun loadRecord(): PersistedRuntimeNotificationSettingsRecord? {
    val encoded = storage.readText(RUNTIME_NOTIFICATION_SETTINGS_FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return null
    }
    return runCatching {
      PersistenceJson.instance.decodeFromString(
        deserializer = PersistedRuntimeNotificationSettingsRecord.serializer(),
        string = encoded,
      )
    }.getOrNull()
  }

  companion object {
    fun fromContext(
      context: Context,
    ): RuntimeNotificationSettingsStore = RuntimeNotificationSettingsStore(
      storage = DirectoryDurableTextStorage(
        File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      ),
    )
  }
}

@Serializable
private data class PersistedRuntimeNotificationSettingsRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val masterEnabled: Boolean = RuntimeNotificationSettingsState().masterEnabled,
  val defaultDeliveryModeId: String =
    RuntimeNotificationSettingsState().defaultDeliveryMode.wireValue,
  val quietHoursEnabled: Boolean = RuntimeNotificationSettingsState().quietHoursEnabled,
  val quietHoursStartMinutes: Int =
    RuntimeNotificationSettingsState().quietHoursStartMinutes,
  val quietHoursEndMinutes: Int =
    RuntimeNotificationSettingsState().quietHoursEndMinutes,
  val approvalRequestsEnabled: Boolean =
    RuntimeNotificationSettingsState().approvalRequestsEnabled,
  val approvalReminderEnabled: Boolean =
    RuntimeNotificationSettingsState().approvalReminderEnabled,
  val taskFinishedEnabled: Boolean =
    RuntimeNotificationSettingsState().taskFinishedEnabled,
  val taskFailedEnabled: Boolean =
    RuntimeNotificationSettingsState().taskFailedEnabled,
  val scheduledWakeEnabled: Boolean =
    RuntimeNotificationSettingsState().scheduledWakeEnabled,
  val backgroundTaskPausedEnabled: Boolean =
    RuntimeNotificationSettingsState().backgroundTaskPausedEnabled,
  val serviceRecoveredEnabled: Boolean =
    RuntimeNotificationSettingsState().serviceRecoveredEnabled,
) {
  fun toState(
    defaults: RuntimeNotificationSettingsState,
  ): RuntimeNotificationSettingsState = defaults.copy(
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

  companion object {
    fun fromState(
      state: RuntimeNotificationSettingsState,
    ): PersistedRuntimeNotificationSettingsRecord =
      PersistedRuntimeNotificationSettingsRecord(
        masterEnabled = state.masterEnabled,
        defaultDeliveryModeId = state.defaultDeliveryMode.wireValue,
        quietHoursEnabled = state.quietHoursEnabled,
        quietHoursStartMinutes = state.quietHoursStartMinutes,
        quietHoursEndMinutes = state.quietHoursEndMinutes,
        approvalRequestsEnabled = state.approvalRequestsEnabled,
        approvalReminderEnabled = state.approvalReminderEnabled,
        taskFinishedEnabled = state.taskFinishedEnabled,
        taskFailedEnabled = state.taskFailedEnabled,
        scheduledWakeEnabled = state.scheduledWakeEnabled,
        backgroundTaskPausedEnabled = state.backgroundTaskPausedEnabled,
        serviceRecoveredEnabled = state.serviceRecoveredEnabled,
      )
  }
}

private fun Int.normalizedMinutesOfDay(): Int =
  ((this % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

private const val MINUTES_PER_DAY: Int = 24 * 60
