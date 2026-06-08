package com.opencray.app

import com.opencray.app.shell.AppShellDestination
import com.opencray.app.shell.AppShellTab
import com.opencray.app.shell.SettingsSubpage

internal fun appShellFlutterDestination(
  destination: AppShellDestination,
): OpenCrayFlutterActivity.Destination = when (destination.selectedTab) {
  AppShellTab.CHAT -> OpenCrayFlutterActivity.Destination.CHAT
  AppShellTab.SKILLS -> OpenCrayFlutterActivity.Destination.SKILLS
  AppShellTab.FILES -> OpenCrayFlutterActivity.Destination.FILES
  AppShellTab.SETTINGS -> destination.settingsSubpage.toFlutterDestination()
}

private fun SettingsSubpage.toFlutterDestination(): OpenCrayFlutterActivity.Destination =
  when (this) {
    SettingsSubpage.HOME -> OpenCrayFlutterActivity.Destination.SETTINGS
    SettingsSubpage.NOTIFICATIONS_BACKGROUND ->
      OpenCrayFlutterActivity.Destination.SETTINGS_NOTIFICATIONS_BACKGROUND
    SettingsSubpage.NOTIFICATION_CHANNELS ->
      OpenCrayFlutterActivity.Destination.SETTINGS_NOTIFICATION_CHANNELS
    SettingsSubpage.WORKSPACE -> OpenCrayFlutterActivity.Destination.SETTINGS_WORKSPACE
    SettingsSubpage.LLM -> OpenCrayFlutterActivity.Destination.SETTINGS_LLM
    SettingsSubpage.MCP -> OpenCrayFlutterActivity.Destination.SETTINGS_MCP
    SettingsSubpage.PRIVACY -> OpenCrayFlutterActivity.Destination.SETTINGS_PRIVACY
    SettingsSubpage.SAFETY -> OpenCrayFlutterActivity.Destination.SETTINGS_SAFETY
    SettingsSubpage.PERSONALIZATION ->
      OpenCrayFlutterActivity.Destination.SETTINGS_PERSONALIZATION
    SettingsSubpage.AGENTS -> OpenCrayFlutterActivity.Destination.SETTINGS_AGENTS
    SettingsSubpage.ABOUT -> OpenCrayFlutterActivity.Destination.SETTINGS_ABOUT
  }
