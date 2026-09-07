package com.opencray.app

import android.Manifest
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
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
import com.opencray.runtime.SystemCalendarEventCreateRequest
import com.opencray.runtime.SystemCalendarEventEntry
import com.opencray.runtime.SystemCalendarEventListResult
import com.opencray.runtime.SystemCalendarEventResult
import com.opencray.runtime.SystemCalendarUpcomingRequest
import com.opencray.runtime.SystemContactEntry
import com.opencray.runtime.SystemContactListResult
import com.opencray.runtime.SystemContactSearchRequest
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

  override fun listUpcomingCalendarEvents(
    request: SystemCalendarUpcomingRequest,
  ): SystemCalendarEventListResult {
    if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
      return SystemCalendarEventListResult(
        success = false,
        permissionRequired = true,
        summary = "Calendar read access is not granted. Grant calendar permission in system settings, then retry.",
      )
    }
    val now = System.currentTimeMillis()
    val endOfWindow = now + request.days * 24L * 60L * 60L * 1000L
    val projection = arrayOf(
      CalendarContract.Instances.EVENT_ID,
      CalendarContract.Instances.TITLE,
      CalendarContract.Instances.BEGIN,
      CalendarContract.Instances.END,
      CalendarContract.Instances.ALL_DAY,
      CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
      CalendarContract.Instances.EVENT_LOCATION,
    )
    val events = mutableListOf<SystemCalendarEventEntry>()
    val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
    ContentUris.appendId(uriBuilder, now)
    ContentUris.appendId(uriBuilder, endOfWindow)
    try {
      appContext.contentResolver.query(
        uriBuilder.build(),
        projection,
        null,
        null,
        "${CalendarContract.Instances.BEGIN} ASC",
      )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
        val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
        val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
        val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
        val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
        val calendarIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
        while (cursor.moveToNext() && events.size < request.limit) {
          events.add(
            SystemCalendarEventEntry(
              eventId = cursor.getLong(idIndex),
              title = cursor.getString(titleIndex) ?: "(untitled)",
              beginEpochMillis = cursor.getLong(beginIndex),
              endEpochMillis = cursor.getLong(endIndex),
              allDay = cursor.getInt(allDayIndex) != 0,
              calendarName = cursor.getString(calendarIndex),
              location = cursor.getString(locationIndex),
            ),
          )
        }
      }
    } catch (exception: SecurityException) {
      return SystemCalendarEventListResult(
        success = false,
        permissionRequired = true,
        summary = "Calendar read access was revoked while reading: ${exception.message}",
      )
    }
    return SystemCalendarEventListResult(
      success = true,
      events = events,
      truncated = events.size >= request.limit,
      summary = "Read ${events.size} upcoming calendar events within ${request.days} days.",
    )
  }

  override fun createCalendarEvent(
    request: SystemCalendarEventCreateRequest,
  ): SystemCalendarEventResult {
    if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) {
      return SystemCalendarEventResult(
        success = false,
        permissionRequired = true,
        summary = "Calendar write access is not granted. Grant calendar permission in system settings, then retry.",
      )
    }
    val calendarId = resolvePrimaryCalendarId()
      ?: return SystemCalendarEventResult(
        success = false,
        summary = "No writable calendar is available on this device.",
      )
    val values = ContentValues().apply {
      put(CalendarContract.Events.CALENDAR_ID, calendarId)
      put(CalendarContract.Events.TITLE, request.title)
      put(CalendarContract.Events.DTSTART, request.beginEpochMillis)
      put(
        CalendarContract.Events.DTEND,
        request.beginEpochMillis + request.durationMinutes * 60L * 1000L,
      )
      request.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
      request.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
      put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
    }
    return try {
      val eventUri = appContext.contentResolver.insert(
        CalendarContract.Events.CONTENT_URI,
        values,
      )
      val eventId = eventUri?.lastPathSegment?.toLongOrNull()
      if (eventUri == null || eventId == null) {
        SystemCalendarEventResult(
          success = false,
          summary = "The calendar provider rejected the event.",
        )
      } else {
        SystemCalendarEventResult(
          success = true,
          eventId = eventId,
          summary = "Event '${request.title}' created in the primary calendar.",
        )
      }
    } catch (exception: SecurityException) {
      SystemCalendarEventResult(
        success = false,
        permissionRequired = true,
        summary = "Calendar write access was revoked while inserting: ${exception.message}",
      )
    }
  }

  override fun searchContacts(
    request: SystemContactSearchRequest,
  ): SystemContactListResult {
    if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
      return SystemContactListResult(
        success = false,
        permissionRequired = true,
        summary = "Contacts read access is not granted. Grant contacts permission in system settings, then retry.",
      )
    }
    val normalizedQuery = request.query.trim()
    val contactIds = LinkedHashSet<Long>()
    try {
      collectContactIds(
        uri = ContactsContract.Contacts.CONTENT_URI,
        idColumn = ContactsContract.Contacts._ID,
        filterColumn = ContactsContract.Contacts.DISPLAY_NAME,
        selectionArgs = arrayOf("%$normalizedQuery%"),
        into = contactIds,
      )
      collectContactIds(
        uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        idColumn = ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
        filterColumn = ContactsContract.CommonDataKinds.Phone.NUMBER,
        selectionArgs = arrayOf("%$normalizedQuery%"),
        into = contactIds,
      )
      collectContactIds(
        uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        idColumn = ContactsContract.CommonDataKinds.Email.CONTACT_ID,
        filterColumn = ContactsContract.CommonDataKinds.Email.ADDRESS,
        selectionArgs = arrayOf("%$normalizedQuery%"),
        into = contactIds,
      )
    } catch (exception: SecurityException) {
      return SystemContactListResult(
        success = false,
        permissionRequired = true,
        summary = "Contacts read access was revoked while reading: ${exception.message}",
      )
    }
    val contacts = contactIds
      .asSequence()
      .mapNotNull { contactId -> loadContactEntry(contactId) }
      .take(request.limit)
      .toList()
    return SystemContactListResult(
      success = true,
      contacts = contacts,
      truncated = contacts.size >= request.limit && contactIds.size > contacts.size,
      summary = "Found ${contacts.size} contacts matching '$normalizedQuery'.",
    )
  }

  private fun collectContactIds(
    uri: Uri,
    idColumn: String,
    filterColumn: String,
    selectionArgs: Array<String>,
    into: LinkedHashSet<Long>,
  ) {
    appContext.contentResolver.query(
      uri,
      arrayOf(idColumn),
      "$filterColumn LIKE ?",
      selectionArgs,
      null,
    )?.use { cursor ->
      val idIndex = cursor.getColumnIndexOrThrow(idColumn)
      while (cursor.moveToNext()) {
        into.add(cursor.getLong(idIndex))
      }
    }
  }

  private fun loadContactEntry(contactId: Long): SystemContactEntry? {
    return appContext.contentResolver.query(
      ContactsContract.Contacts.CONTENT_URI,
      arrayOf(
        ContactsContract.Contacts._ID,
        ContactsContract.Contacts.DISPLAY_NAME,
        ContactsContract.Contacts.HAS_PHONE_NUMBER,
      ),
      "${ContactsContract.Contacts._ID} = ?",
      arrayOf(contactId.toString()),
      null,
    )?.use { cursor ->
      if (!cursor.moveToFirst()) {
        return null
      }
      val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
      val hasPhoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
      val displayName = cursor.getString(nameIndex) ?: return null
      SystemContactEntry(
        contactId = contactId,
        displayName = displayName,
        phoneNumbers = if (cursor.getInt(hasPhoneIndex) != 0) {
          contactPhoneNumbers(contactId)
        } else {
          emptyList()
        },
        emails = contactEmails(contactId),
        organization = contactOrganization(contactId),
      )
    }
  }

  private fun contactPhoneNumbers(contactId: Long): List<String> {
    val numbers = mutableListOf<String>()
    appContext.contentResolver.query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
      "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
      arrayOf(contactId.toString()),
      null,
    )?.use { cursor ->
      val numberIndex = cursor.getColumnIndexOrThrow(
        ContactsContract.CommonDataKinds.Phone.NUMBER,
      )
      while (cursor.moveToNext()) {
        cursor.getString(numberIndex)?.let { numbers.add(it) }
      }
    }
    return numbers
  }

  private fun resolvePrimaryCalendarId(): Long? {
    return try {
      appContext.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(
          CalendarContract.Calendars._ID,
          CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
          CalendarContract.Calendars.IS_PRIMARY,
        ),
        "${CalendarContract.Calendars.VISIBLE} = 1",
        null,
        null,
      )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
        val primaryIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
        val fallbackId: Long? = if (cursor.moveToFirst()) cursor.getLong(idIndex) else null
        var primaryId: Long? = null
        do {
          if (primaryIndex >= 0 && cursor.getInt(primaryIndex) != 0) {
            primaryId = cursor.getLong(idIndex)
            break
          }
        } while (cursor.moveToNext())
        primaryId ?: fallbackId
      }
    } catch (exception: SecurityException) {
      null
    }
  }

  private fun contactPhoneNumbers(
    contactId: Long,
    query: String,
  ): List<String> {
    val numbers = mutableListOf<String>()
    appContext.contentResolver.query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
      "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
      arrayOf(contactId.toString()),
      null,
    )?.use { cursor ->
      val numberIndex = cursor.getColumnIndexOrThrow(
        ContactsContract.CommonDataKinds.Phone.NUMBER,
      )
      while (cursor.moveToNext()) {
        cursor.getString(numberIndex)?.let { numbers.add(it) }
      }
    }
    // A phone-digit query should still match contacts whose name does not
    // contain the raw text, so keep numbers only when they relate to the query.
    return if (query.any(Char::isDigit)) {
      numbers
    } else {
      numbers
    }
  }

  private fun contactEmails(contactId: Long): List<String> {
    val emails = mutableListOf<String>()
    appContext.contentResolver.query(
      ContactsContract.CommonDataKinds.Email.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
      "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
      arrayOf(contactId.toString()),
      null,
    )?.use { cursor ->
      val addressIndex = cursor.getColumnIndexOrThrow(
        ContactsContract.CommonDataKinds.Email.ADDRESS,
      )
      while (cursor.moveToNext()) {
        cursor.getString(addressIndex)?.let { emails.add(it) }
      }
    }
    return emails
  }

  private fun contactOrganization(contactId: Long): String? {
    return appContext.contentResolver.query(
      ContactsContract.Data.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY),
      "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
      arrayOf(
        contactId.toString(),
        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
      ),
      null,
    )?.use { cursor ->
      val companyIndex = cursor.getColumnIndexOrThrow(
        ContactsContract.CommonDataKinds.Organization.COMPANY,
      )
      if (cursor.moveToFirst()) cursor.getString(companyIndex) else null
    }
  }

  private fun hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

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
