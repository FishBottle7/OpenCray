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
    val flutterDestination = when (destination.selectedTab) {
      AppShellTab.CHAT -> OpenCrayFlutterActivity.Destination.CHAT
      AppShellTab.SKILLS -> OpenCrayFlutterActivity.Destination.SKILLS
      AppShellTab.FILES -> OpenCrayFlutterActivity.Destination.FILES
      AppShellTab.SETTINGS -> destination.settingsSubpage.toFlutterDestination()
    }
    return OpenCrayFlutterActivity.intent(this, flutterDestination).apply {
      putExtras(intent)
      addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
  }

  private fun SettingsSubpage.toFlutterDestination(): OpenCrayFlutterActivity.Destination =
    when (this) {
      SettingsSubpage.HOME -> OpenCrayFlutterActivity.Destination.SETTINGS
      SettingsSubpage.WORKSPACE -> OpenCrayFlutterActivity.Destination.SETTINGS_WORKSPACE
      SettingsSubpage.LLM -> OpenCrayFlutterActivity.Destination.SETTINGS_LLM
      SettingsSubpage.MCP -> OpenCrayFlutterActivity.Destination.SETTINGS_MCP
      SettingsSubpage.PRIVACY -> OpenCrayFlutterActivity.Destination.SETTINGS_PRIVACY
      SettingsSubpage.SAFETY -> OpenCrayFlutterActivity.Destination.SETTINGS_SAFETY
      SettingsSubpage.ABOUT -> OpenCrayFlutterActivity.Destination.SETTINGS_ABOUT
      SettingsSubpage.PERSONALIZATION ->
        OpenCrayFlutterActivity.Destination.SETTINGS_PERSONALIZATION
    }

  private companion object {
    const val STATE_SELECTED_TAB = "selected_tab"
    const val STATE_SETTINGS_SUBPAGE = "settings_subpage"
  }
}
