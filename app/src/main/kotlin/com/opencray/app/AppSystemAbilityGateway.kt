package com.opencray.app

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.opencray.runtime.SystemAbilityGateway
import com.opencray.runtime.SystemAlarmCreateRequest
import com.opencray.runtime.SystemAlarmResult
import com.opencray.runtime.SystemAppEntry
import com.opencray.runtime.SystemAppListRequest
import com.opencray.runtime.SystemAppListResult
import com.opencray.runtime.SystemAppOpenRequest
import com.opencray.runtime.SystemAppOpenResult
import com.opencray.runtime.SystemNotificationRequest
import com.opencray.runtime.SystemNotificationResult
import com.opencray.runtime.SystemSettingsOpenRequest
import com.opencray.runtime.SystemSettingsOpenResult
import com.opencray.runtime.SystemTimerRequest
import com.opencray.runtime.SystemTimerResult
import java.util.Calendar

/**
 * App-side SystemAbilityGateway: forwards agent tool calls to Android system
 * surfaces. Runs inside the runtime service process, so every outgoing intent
 * carries FLAG_ACTIVITY_NEW_TASK and must resolve without user interaction.
 */
class AppSystemAbilityGateway private constructor(
  private val appContext: Context,
) : SystemAbilityGateway {

  override fun createAlarm(request: SystemAlarmCreateRequest): SystemAlarmResult {
    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
      putExtra(AlarmClock.EXTRA_HOUR, request.hour)
      putExtra(AlarmClock.EXTRA_MINUTES, request.minute)
      putExtra(AlarmClock.EXTRA_SKIP_UI, true)
      request.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
      if (request.daysOfWeek.isNotEmpty()) {
        // ISO 1 (Monday) .. 7 (Sunday) maps directly to Calendar day constants.
        putExtra(AlarmClock.EXTRA_DAYS, request.daysOfWeek.toIntArray())
      }
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return if (resolveActivity(intent) == null) {
      SystemAlarmResult(
        success = false,
        available = false,
        summary = "No clock app is available to create alarms on this device.",
      )
    } else {
      val launched = startActivitySafely(intent)
      SystemAlarmResult(
        success = launched,
        summary = if (launched) {
          "Alarm requested at %02d:%02d via the clock app.".format(
            request.hour,
            request.minute,
          )
        } else {
          "The clock app rejected the alarm request."
        },
      )
    }
  }

  override fun startTimer(request: SystemTimerRequest): SystemTimerResult {
    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
      putExtra(AlarmClock.EXTRA_LENGTH, request.durationSeconds)
      putExtra(AlarmClock.EXTRA_SKIP_UI, true)
      request.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return if (resolveActivity(intent) == null) {
      SystemTimerResult(
        success = false,
        available = false,
        summary = "No clock app is available to start timers on this device.",
      )
    } else {
      val launched = startActivitySafely(intent)
      SystemTimerResult(
        success = launched,
        summary = if (launched) {
          "Timer of ${request.durationSeconds}s requested via the clock app."
        } else {
          "The clock app rejected the timer request."
        },
      )
    }
  }

  override fun postNotification(
    request: SystemNotificationRequest,
  ): SystemNotificationResult {
    val notificationManager = NotificationManagerCompat.from(appContext)
    if (!notificationManager.areNotificationsEnabled()) {
      return SystemNotificationResult(
        success = false,
        permissionRequired = true,
        summary = "Notifications are disabled for this app in system settings.",
      )
    }
    RuntimeNotificationChannelRegistry.ensureRegistered(appContext)
    val mainActivityIntent = appContext.packageManager.getLaunchIntentForPackage(
      appContext.packageName,
    )?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    val contentIntent = mainActivityIntent?.let {
      PendingIntent.getActivity(
        appContext,
        SYSTEM_NOTIFICATION_REQUEST_CODE,
        it,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }
    val notification = NotificationCompat.Builder(
      appContext,
      RuntimeNotificationChannelRegistry.CHANNEL_RUNTIME_COMPLETION,
    )
      .setSmallIcon(android.R.drawable.stat_notify_chat)
      .setContentTitle(request.title)
      .setContentText(request.body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(request.body))
      .setAutoCancel(true)
      .apply { contentIntent?.let { intent -> setContentIntent(intent) } }
      .build()
    return try {
      notificationManager.notify(
        SYSTEM_NOTIFICATION_TAG,
        nextNotificationId(),
        notification,
      )
      SystemNotificationResult(
        success = true,
        summary = "Notification posted to the system shade.",
      )
    } catch (exception: SecurityException) {
      SystemNotificationResult(
        success = false,
        permissionRequired = true,
        summary = "Notification permission is required: ${exception.message}",
      )
    }
  }

  override fun listApps(request: SystemAppListRequest): SystemAppListResult {
    val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
      addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val resolveFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      0
    } else {
      @Suppress("DEPRECATION")
      PackageManager.GET_DISABLED_COMPONENTS
    }
    val resolved = appContext.packageManager.queryIntentActivities(
      launcherIntent,
      resolveFlags,
    )
    val normalizedQuery = request.query?.lowercase()
    val entries = resolved
      .asSequence()
      .mapNotNull { resolveInfo ->
        val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
        val label = runCatching {
          appContext.packageManager.getApplicationLabel(resolveInfo.activityInfo.applicationInfo)
            ?.toString()
        }.getOrNull() ?: packageName
        packageName to label
      }
      .filter { (packageName, label) ->
        normalizedQuery == null ||
          packageName.lowercase().contains(normalizedQuery) ||
          label.lowercase().contains(normalizedQuery)
      }
      .distinctBy { (packageName, _) -> packageName }
      .sortedBy { (_, label) -> label.lowercase() }
      .toList()
    val limited = entries.take(request.limit)
    return SystemAppListResult(
      success = true,
      apps = limited.map { (packageName, label) ->
        SystemAppEntry(packageName = packageName, label = label)
      },
      truncated = limited.size < entries.size,
    )
  }

  override fun openApp(request: SystemAppOpenRequest): SystemAppOpenResult {
    val launchIntent = appContext.packageManager.getLaunchIntentForPackage(request.packageName)
    if (launchIntent == null) {
      return SystemAppOpenResult(
        success = false,
        available = false,
      )
    }
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val launched = startActivitySafely(launchIntent)
    val label = runCatching {
      appContext.packageManager
        .getApplicationInfo(request.packageName, 0)
        .let { info -> appContext.packageManager.getApplicationLabel(info)?.toString() }
    }.getOrNull()
    return SystemAppOpenResult(
      success = launched,
      available = true,
      label = label,
    )
  }

  override fun openSettings(
    request: SystemSettingsOpenRequest,
  ): SystemSettingsOpenResult {
    val intent = when (request.page) {
      "battery_optimization" ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
          null
        }

      "notifications" ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
          }
        } else {
          Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = packageUri()
          }
        }

      "exact_alarms" ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = packageUri()
          }
        } else {
          null
        }

      "app_details" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = packageUri()
      }

      else -> null
    }?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    if (intent == null) {
      return SystemSettingsOpenResult(
        success = false,
        page = request.page,
        summary = "Settings page '${request.page}' is not supported on this Android version.",
      )
    }
    val launched = startActivitySafely(intent)
    return SystemSettingsOpenResult(
      success = launched,
      page = request.page,
      summary = if (launched) {
        "Opened the '${request.page}' settings page."
      } else {
        "The '${request.page}' settings page could not be opened."
      },
    )
  }

  private fun resolveActivity(intent: Intent) = intent.resolveActivity(appContext.packageManager)

  private fun startActivitySafely(intent: Intent): Boolean = try {
    appContext.startActivity(intent)
    true
  } catch (exception: ActivityNotFoundException) {
    false
  } catch (exception: SecurityException) {
    false
  }

  private fun packageUri(): Uri = Uri.parse("package:${appContext.packageName}")

  private fun nextNotificationId(): Int {
    val calendar = Calendar.getInstance()
    return (calendar.get(Calendar.HOUR_OF_DAY) * 100_000) +
      (calendar.get(Calendar.MINUTE) * 1_000) +
      (calendar.get(Calendar.SECOND) / 10)
  }

  companion object {
    private const val SYSTEM_NOTIFICATION_TAG = "system_ability_tool"
    private const val SYSTEM_NOTIFICATION_REQUEST_CODE = 40_911

    fun fromContext(context: Context): AppSystemAbilityGateway =
      AppSystemAbilityGateway(context.applicationContext)
  }
}
