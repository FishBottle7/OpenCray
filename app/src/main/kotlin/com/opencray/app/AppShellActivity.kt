package com.opencray.app

import android.content.Intent
import android.os.Bundle
import com.opencray.app.shell.AppShellDestination
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.AppShellTab
import com.opencray.app.shell.SettingsSubpage

class AppShellActivity : LocalizedActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val destination = AppShellLaunchStateResolver.resolve(
      restoredTabRaw = savedInstanceState?.getString(STATE_SELECTED_TAB),
      restoredSettingsSubpageRaw = savedInstanceState?.getString(STATE_SETTINGS_SUBPAGE),
      hasRestoredState = savedInstanceState != null,
      startTabRaw = intent.getStringExtra(AppShellNavigationExtras.EXTRA_START_TAB),
      startSettingsSubpageRaw = intent.getStringExtra(AppShellNavigationExtras.EXTRA_START_SETTINGS_PAGE),
      hasStartExtras =
        intent.hasExtra(AppShellNavigationExtras.EXTRA_START_TAB) ||
          intent.hasExtra(AppShellNavigationExtras.EXTRA_START_SETTINGS_PAGE),
      persistedDestination = AppShellStateStore.fromContext(this).load(),
    )

    AppShellStateStore.fromContext(this).save(destination)
    startActivity(forwardIntent(destination))
    finish()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(STATE_SELECTED_TAB, intent.getStringExtra(AppShellNavigationExtras.EXTRA_START_TAB))
    outState.putString(
      STATE_SETTINGS_SUBPAGE,
      intent.getStringExtra(AppShellNavigationExtras.EXTRA_START_SETTINGS_PAGE),
    )
  }

  private fun forwardIntent(destination: AppShellDestination): Intent {
    val notificationSessionId = intent.getStringExtra(
      RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SESSION_ID,
    )?.trim()?.takeIf(String::isNotBlank)
    val notificationScheduleId = intent.getStringExtra(
      RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SCHEDULE_ID,
    )?.trim()?.takeIf(String::isNotBlank)
    val flutterDestination = appShellFlutterDestination(destination)
    val chatSessionId = notificationSessionId.takeIf {
      destination.selectedTab == AppShellTab.CHAT
    }
    return OpenCrayFlutterActivity.intent(
      this,
      flutterDestination,
      chatSessionId = chatSessionId,
      scheduleId = notificationScheduleId.takeIf {
        destination.settingsSubpage == SettingsSubpage.SCHEDULED_TASKS
      },
    ).apply {
      putExtras(intent)
      addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
  }

  private companion object {
    const val STATE_SELECTED_TAB = "selected_tab"
    const val STATE_SETTINGS_SUBPAGE = "settings_subpage"
  }
}
