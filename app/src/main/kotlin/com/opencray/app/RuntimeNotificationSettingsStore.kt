package com.opencray.app

import android.content.Context
import android.content.SharedPreferences

private const val DEFAULT_RUNTIME_NOTIFICATION_SETTINGS_PREFERENCES =
  "opencray.runtime-notification-settings"

internal object RuntimeNotificationSettingsStoreKeys {
  const val MASTER_ENABLED = "master_enabled"
  const val DEFAULT_DELIVERY_MODE_ID = "default_delivery_mode_id"
  const val QUIET_HOURS_ENABLED = "quiet_hours_enabled"
  const val QUIET_HOURS_START_MINUTES = "quiet_hours_start_minutes"
  const val QUIET_HOURS_END_MINUTES = "quiet_hours_end_minutes"
  const val APPROVAL_REQUESTS_ENABLED = "approval_requests_enabled"
  const val APPROVAL_REMINDER_ENABLED = "approval_reminder_enabled"
  const val TASK_FINISHED_ENABLED = "task_finished_enabled"
  const val TASK_FAILED_ENABLED = "task_failed_enabled"
  const val SCHEDULED_WAKE_ENABLED = "scheduled_wake_enabled"
  const val BACKGROUND_TASK_PAUSED_ENABLED = "background_task_paused_enabled"
  const val SERVICE_RECOVERED_ENABLED = "service_recovered_enabled"
}

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

internal interface RuntimeNotificationSettingsKeyValueStore {
  fun getBoolean(key: String): Boolean?

  fun putBoolean(
    key: String,
    value: Boolean,
  )

  fun getInt(key: String): Int?

  fun putInt(
    key: String,
    value: Int,
  )

  fun getString(key: String): String?

  fun putString(
    key: String,
    value: String,
  )

  fun clear()
}

internal class InMemoryRuntimeNotificationSettingsKeyValueStore(
  initialValues: Map<String, String> = emptyMap(),
) : RuntimeNotificationSettingsKeyValueStore {
  private val values = linkedMapOf<String, String>().apply {
    putAll(initialValues)
  }

  override fun getBoolean(key: String): Boolean? = values[key]?.toBooleanStrictOrNull()

  override fun putBoolean(
    key: String,
    value: Boolean,
  ) {
    values[key] = value.toString()
  }

  override fun getInt(key: String): Int? = values[key]?.toIntOrNull()

  override fun putInt(
    key: String,
    value: Int,
  ) {
    values[key] = value.toString()
  }

  override fun getString(key: String): String? = values[key]

  override fun putString(
    key: String,
    value: String,
  ) {
    values[key] = value
  }

  override fun clear() {
    values.clear()
  }
}

internal class SharedPreferencesRuntimeNotificationSettingsKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : RuntimeNotificationSettingsKeyValueStore {
  override fun getBoolean(key: String): Boolean? =
    if (sharedPreferences.contains(key)) sharedPreferences.getBoolean(key, false) else null

  override fun putBoolean(
    key: String,
    value: Boolean,
  ) {
    sharedPreferences.edit().putBoolean(key, value).apply()
  }

  override fun getInt(key: String): Int? =
    if (sharedPreferences.contains(key)) sharedPreferences.getInt(key, 0) else null

  override fun putInt(
    key: String,
    value: Int,
  ) {
    sharedPreferences.edit().putInt(key, value).apply()
  }

  override fun getString(key: String): String? =
    if (sharedPreferences.contains(key)) sharedPreferences.getString(key, null) else null

  override fun putString(
    key: String,
    value: String,
  ) {
    sharedPreferences.edit().putString(key, value).apply()
  }

  override fun clear() {
    sharedPreferences.edit().clear().apply()
  }
}

internal class RuntimeNotificationSettingsStore(
  private val keyValueStore: RuntimeNotificationSettingsKeyValueStore,
) {
  fun load(
    defaults: RuntimeNotificationSettingsState = RuntimeNotificationSettingsState(),
  ): RuntimeNotificationSettingsState = defaults.copy(
    masterEnabled =
      keyValueStore.getBoolean(RuntimeNotificationSettingsStoreKeys.MASTER_ENABLED)
        ?: defaults.masterEnabled,
    defaultDeliveryMode = RuntimeNotificationDeliveryMode.fromWireValue(
      keyValueStore.getString(RuntimeNotificationSettingsStoreKeys.DEFAULT_DELIVERY_MODE_ID),
    ),
    quietHoursEnabled =
      keyValueStore.getBoolean(RuntimeNotificationSettingsStoreKeys.QUIET_HOURS_ENABLED)
        ?: defaults.quietHoursEnabled,
    quietHoursStartMinutes =
      keyValueStore.getInt(RuntimeNotificationSettingsStoreKeys.QUIET_HOURS_START_MINUTES)
        ?: defaults.quietHoursStartMinutes,
    quietHoursEndMinutes =
      keyValueStore.getInt(RuntimeNotificationSettingsStoreKeys.QUIET_HOURS_END_MINUTES)
        ?: defaults.quietHoursEndMinutes,
    approvalRequestsEnabled =
      keyValueStore.getBoolean(RuntimeNotificationSettingsStoreKeys.APPROVAL_REQUESTS_ENABLED)
        ?: defaults.approvalRequestsEnabled,
    approvalReminderEnabled =
      keyValueStore.getBoolean(RuntimeNotificationSettingsStoreKeys.APPROVAL_REMINDER_ENABLED)
        ?: defaults.approvalReminderEnabled,
    taskFinishedEnabled =
      keyValueStore.getBoolean(RuntimeNotificationSettingsStoreKeys.TASK_FINISHED_ENABLED)
        ?: defaults.taskFinishedEnabled,
    taskFailedEnabled =
      keyValueStore.getBoolean(RuntimeNotificationSettingsStoreKeys.TASK_FAILED_ENABLED)
        ?: defaults.taskFailedEnabled,
    scheduledWakeEnabled =
      keyValueStore.getBoolean(RuntimeNotificationSettingsStoreKeys.SCHEDULED_WAKE_ENABLED)
        ?: defaults.scheduledWakeEnabled,
    backgroundTaskPausedEnabled =
      keyValueStore.getBoolean(RuntimeNotificationSettingsStoreKeys.BACKGROUND_TASK_PAUSED_ENABLED)
        ?: defaults.backgroundTaskPausedEnabled,
    serviceRecoveredEnabled =
      keyValueStore.getBoolean(RuntimeNotificationSettingsStoreKeys.SERVICE_RECOVERED_ENABLED)
        ?: defaults.serviceRecoveredEnabled,
  ).sanitized()

  fun save(state: RuntimeNotificationSettingsState) {
    val sanitized = state.sanitized()
    keyValueStore.putBoolean(
      RuntimeNotificationSettingsStoreKeys.MASTER_ENABLED,
      sanitized.masterEnabled,
    )
    keyValueStore.putString(
      RuntimeNotificationSettingsStoreKeys.DEFAULT_DELIVERY_MODE_ID,
      sanitized.defaultDeliveryMode.wireValue,
    )
    keyValueStore.putBoolean(
      RuntimeNotificationSettingsStoreKeys.QUIET_HOURS_ENABLED,
      sanitized.quietHoursEnabled,
    )
    keyValueStore.putInt(
      RuntimeNotificationSettingsStoreKeys.QUIET_HOURS_START_MINUTES,
      sanitized.quietHoursStartMinutes,
    )
    keyValueStore.putInt(
      RuntimeNotificationSettingsStoreKeys.QUIET_HOURS_END_MINUTES,
      sanitized.quietHoursEndMinutes,
    )
    keyValueStore.putBoolean(
      RuntimeNotificationSettingsStoreKeys.APPROVAL_REQUESTS_ENABLED,
      sanitized.approvalRequestsEnabled,
    )
    keyValueStore.putBoolean(
      RuntimeNotificationSettingsStoreKeys.APPROVAL_REMINDER_ENABLED,
      sanitized.approvalReminderEnabled,
    )
    keyValueStore.putBoolean(
      RuntimeNotificationSettingsStoreKeys.TASK_FINISHED_ENABLED,
      sanitized.taskFinishedEnabled,
    )
    keyValueStore.putBoolean(
      RuntimeNotificationSettingsStoreKeys.TASK_FAILED_ENABLED,
      sanitized.taskFailedEnabled,
    )
    keyValueStore.putBoolean(
      RuntimeNotificationSettingsStoreKeys.SCHEDULED_WAKE_ENABLED,
      sanitized.scheduledWakeEnabled,
    )
    keyValueStore.putBoolean(
      RuntimeNotificationSettingsStoreKeys.BACKGROUND_TASK_PAUSED_ENABLED,
      sanitized.backgroundTaskPausedEnabled,
    )
    keyValueStore.putBoolean(
      RuntimeNotificationSettingsStoreKeys.SERVICE_RECOVERED_ENABLED,
      sanitized.serviceRecoveredEnabled,
    )
  }

  fun clear() {
    keyValueStore.clear()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_RUNTIME_NOTIFICATION_SETTINGS_PREFERENCES,
    ): RuntimeNotificationSettingsStore = RuntimeNotificationSettingsStore(
      keyValueStore = SharedPreferencesRuntimeNotificationSettingsKeyValueStore(
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      ),
    )
  }
}

private fun Int.normalizedMinutesOfDay(): Int =
  ((this % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

private const val MINUTES_PER_DAY: Int = 24 * 60
