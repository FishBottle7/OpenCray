package com.opencray.app

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

internal object StrongBackgroundActionIds {
  const val OPEN_NOTIFICATION_SETTINGS: String = "open_notification_settings"
  const val OPEN_EXACT_ALARM_SETTINGS: String = "open_exact_alarm_settings"
  const val OPEN_BATTERY_OPTIMIZATION_SETTINGS: String = "open_battery_optimization_settings"
  const val REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: String =
    "request_ignore_battery_optimizations"
}

internal object StrongBackgroundTierIds {
  const val BASELINE: String = "baseline"
  const val STRONG_BACKGROUND: String = "strong_background"
}

internal interface StrongBackgroundSettingsAccess {
  fun loadSnapshot(): Map<String, Any?>

  fun performAction(actionId: String): Map<String, Any?>
}

internal object NoOpStrongBackgroundSettingsAccess : StrongBackgroundSettingsAccess {
  override fun loadSnapshot(): Map<String, Any?> = StrongBackgroundSnapshotProjector.project(
    StrongBackgroundCapabilityState(
      notificationPermissionRequired = false,
      notificationPermissionGranted = true,
      notificationsEnabled = false,
      notificationSettingsSupported = false,
      exactAlarmAccessRequired = false,
      exactAlarmAccessGranted = false,
      exactAlarmSettingsSupported = false,
      batteryOptimizationSupported = false,
      batteryOptimizationExempt = false,
      batteryOptimizationSettingsSupported = false,
      directBatteryOptimizationRequestSupported = false,
    ),
  ) + mapOf(
    "available" to false,
  )

  override fun performAction(actionId: String): Map<String, Any?> = actionResult(
    actionId = actionId,
    launched = false,
    available = false,
    reason = "unavailable",
  )
}

internal data class StrongBackgroundCapabilityState(
  val notificationPermissionRequired: Boolean,
  val notificationPermissionGranted: Boolean,
  val notificationsEnabled: Boolean,
  val notificationSettingsSupported: Boolean,
  val exactAlarmAccessRequired: Boolean,
  val exactAlarmAccessGranted: Boolean,
  val exactAlarmSettingsSupported: Boolean,
  val batteryOptimizationSupported: Boolean,
  val batteryOptimizationExempt: Boolean,
  val batteryOptimizationSettingsSupported: Boolean,
  val directBatteryOptimizationRequestSupported: Boolean,
)

internal object StrongBackgroundSnapshotProjector {
  fun project(
    state: StrongBackgroundCapabilityState,
  ): Map<String, Any?> {
    val notificationsConfigured = state.notificationsEnabled &&
      (!state.notificationPermissionRequired || state.notificationPermissionGranted)
    val exactAlarmsConfigured = !state.exactAlarmAccessRequired || state.exactAlarmAccessGranted
    val batteryOptimizationConfigured = !state.batteryOptimizationSupported ||
      state.batteryOptimizationExempt
    val recommendedActionIds = buildList {
      if (!notificationsConfigured && state.notificationSettingsSupported) {
        add(StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS)
      }
      if (!exactAlarmsConfigured && state.exactAlarmSettingsSupported) {
        add(StrongBackgroundActionIds.OPEN_EXACT_ALARM_SETTINGS)
      }
      if (!batteryOptimizationConfigured) {
        when {
          state.directBatteryOptimizationRequestSupported ->
            add(StrongBackgroundActionIds.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)

          state.batteryOptimizationSettingsSupported ->
            add(StrongBackgroundActionIds.OPEN_BATTERY_OPTIMIZATION_SETTINGS)
        }
      }
    }
    val tierId = if (notificationsConfigured &&
      exactAlarmsConfigured &&
      batteryOptimizationConfigured
    ) {
      StrongBackgroundTierIds.STRONG_BACKGROUND
    } else {
      StrongBackgroundTierIds.BASELINE
    }
    val availableActionIds = setOfNotNull(
      StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS.takeIf {
        state.notificationSettingsSupported
      },
      StrongBackgroundActionIds.OPEN_EXACT_ALARM_SETTINGS.takeIf {
        state.exactAlarmSettingsSupported
      },
      StrongBackgroundActionIds.OPEN_BATTERY_OPTIMIZATION_SETTINGS.takeIf {
        state.batteryOptimizationSettingsSupported
      },
      StrongBackgroundActionIds.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.takeIf {
        state.directBatteryOptimizationRequestSupported
      },
    )
    return mapOf(
      "source" to "strong-background",
      "available" to true,
      "tierId" to tierId,
      "setupComplete" to (notificationsConfigured &&
        exactAlarmsConfigured &&
        batteryOptimizationConfigured),
      "recommendedActionIds" to recommendedActionIds,
      "notifications" to mapOf(
        "permissionRequired" to state.notificationPermissionRequired,
        "permissionGranted" to state.notificationPermissionGranted,
        "enabled" to state.notificationsEnabled,
        "configured" to notificationsConfigured,
      ),
      "exactAlarms" to mapOf(
        "accessRequired" to state.exactAlarmAccessRequired,
        "accessGranted" to state.exactAlarmAccessGranted,
        "configured" to exactAlarmsConfigured,
      ),
      "batteryOptimization" to mapOf(
        "supported" to state.batteryOptimizationSupported,
        "exempt" to state.batteryOptimizationExempt,
        "configured" to batteryOptimizationConfigured,
      ),
      "actions" to listOf(
        actionDescriptor(
          StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
          availableActionIds,
          recommendedActionIds,
        ),
        actionDescriptor(
          StrongBackgroundActionIds.OPEN_EXACT_ALARM_SETTINGS,
          availableActionIds,
          recommendedActionIds,
        ),
        actionDescriptor(
          StrongBackgroundActionIds.OPEN_BATTERY_OPTIMIZATION_SETTINGS,
          availableActionIds,
          recommendedActionIds,
        ),
        actionDescriptor(
          StrongBackgroundActionIds.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          availableActionIds,
          recommendedActionIds,
        ),
      ),
    )
  }

  private fun actionDescriptor(
    actionId: String,
    availableActionIds: Set<String>,
    recommendedActionIds: List<String>,
  ): Map<String, Any?> = mapOf(
    "id" to actionId,
    "available" to availableActionIds.contains(actionId),
    "recommended" to recommendedActionIds.contains(actionId),
  )
}

internal class AndroidStrongBackgroundSettingsAccess(
  private val appContext: Context,
  private val alarmManagerProvider: () -> AlarmManager? = {
    appContext.getSystemService(AlarmManager::class.java)
  },
  private val powerManagerProvider: () -> PowerManager? = {
    appContext.getSystemService(PowerManager::class.java)
  },
  private val notificationsEnabledProvider: () -> Boolean = {
    NotificationManagerCompat.from(appContext).areNotificationsEnabled()
  },
  private val notificationPermissionGrantedProvider: () -> Boolean = {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      true
    } else {
      ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.POST_NOTIFICATIONS,
      ) == PackageManager.PERMISSION_GRANTED
    }
  },
  private val launchIntent: (Intent) -> Boolean = { intent ->
    runCatching {
      appContext.startActivity(intent)
      true
    }.getOrDefault(false)
  },
) : StrongBackgroundSettingsAccess {
  override fun loadSnapshot(): Map<String, Any?> =
    StrongBackgroundSnapshotProjector.project(loadCapabilityState())

  override fun performAction(actionId: String): Map<String, Any?> {
    val state = loadCapabilityState()
    val availableActionIds = availableActionIds(state)
    if (!availableActionIds.contains(actionId)) {
      return actionResult(
        actionId = actionId,
        launched = false,
        available = false,
        reason = "unsupported_action",
      )
    }
    if (actionId == StrongBackgroundActionIds.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
      val directIntent = buildIntent(actionId)
      if (directIntent != null && launchIntent(directIntent)) {
        return actionResult(
          actionId = actionId,
          launched = true,
          available = true,
        )
      }
      val fallbackIntent = buildIntent(StrongBackgroundActionIds.OPEN_BATTERY_OPTIMIZATION_SETTINGS)
      if (fallbackIntent != null && launchIntent(fallbackIntent)) {
        return actionResult(
          actionId = actionId,
          launched = true,
          available = true,
          fallbackActionId = StrongBackgroundActionIds.OPEN_BATTERY_OPTIMIZATION_SETTINGS,
        )
      }
      return actionResult(
        actionId = actionId,
        launched = false,
        available = true,
        reason = "launch_failed",
      )
    }
    val intent = buildIntent(actionId)
    if (intent == null) {
      return actionResult(
        actionId = actionId,
        launched = false,
        available = false,
        reason = "unsupported_action",
      )
    }
    return actionResult(
      actionId = actionId,
      launched = launchIntent(intent),
      available = true,
      reason = "launch_failed",
    )
  }

  private fun loadCapabilityState(): StrongBackgroundCapabilityState {
    val alarmManager = alarmManagerProvider()
    val powerManager = powerManagerProvider()
    val notificationPermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val exactAlarmAccessRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val batteryOptimizationSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
      powerManager != null
    return StrongBackgroundCapabilityState(
      notificationPermissionRequired = notificationPermissionRequired,
      notificationPermissionGranted = notificationPermissionGrantedProvider(),
      notificationsEnabled = notificationsEnabledProvider(),
      notificationSettingsSupported = true,
      exactAlarmAccessRequired = exactAlarmAccessRequired,
      exactAlarmAccessGranted = when {
        alarmManager == null -> false
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> true
        else -> alarmManager.canScheduleExactAlarms()
      },
      exactAlarmSettingsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
      batteryOptimizationSupported = batteryOptimizationSupported,
      batteryOptimizationExempt = when {
        !batteryOptimizationSupported -> true
        else -> powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true
      },
      batteryOptimizationSettingsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M,
      directBatteryOptimizationRequestSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M,
    )
  }

  private fun availableActionIds(
    state: StrongBackgroundCapabilityState,
  ): Set<String> = buildSet {
    if (state.notificationSettingsSupported) {
      add(StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS)
    }
    if (state.exactAlarmSettingsSupported) {
      add(StrongBackgroundActionIds.OPEN_EXACT_ALARM_SETTINGS)
    }
    if (state.batteryOptimizationSettingsSupported) {
      add(StrongBackgroundActionIds.OPEN_BATTERY_OPTIMIZATION_SETTINGS)
    }
    if (state.directBatteryOptimizationRequestSupported) {
      add(StrongBackgroundActionIds.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }
  }

  private fun buildIntent(actionId: String): Intent? = when (actionId) {
    StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS -> Intent(
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Settings.ACTION_APP_NOTIFICATION_SETTINGS
      } else {
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
      },
    ).apply {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        putExtra("app_package", appContext.packageName)
        putExtra("app_uid", appContext.applicationInfo.uid)
      } else {
        data = packageUri()
      }
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    StrongBackgroundActionIds.OPEN_EXACT_ALARM_SETTINGS ->
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        null
      } else {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
          data = packageUri()
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      }

    StrongBackgroundActionIds.OPEN_BATTERY_OPTIMIZATION_SETTINGS ->
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        null
      } else {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      }

    StrongBackgroundActionIds.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ->
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        null
      } else {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
          data = packageUri()
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      }

    else -> null
  }

  private fun packageUri(): Uri = Uri.parse("package:${appContext.packageName}")

  companion object {
    fun fromContext(context: Context): StrongBackgroundSettingsAccess =
      AndroidStrongBackgroundSettingsAccess(context.applicationContext)
  }
}

private fun actionResult(
  actionId: String,
  launched: Boolean,
  available: Boolean,
  reason: String? = null,
  fallbackActionId: String? = null,
): Map<String, Any?> = buildMap {
  put("source", "strong-background-action")
  put("actionId", actionId)
  put("available", available)
  put("launched", launched)
  if (!launched && reason != null) {
    put("reason", reason)
  }
  if (fallbackActionId != null) {
    put("fallbackActionId", fallbackActionId)
  }
}
