package com.opencray.app.shell

enum class AppShellTab(
  val routeKey: String,
) {
  CHAT("chat"),
  SKILLS("skills"),
  FILES("files"),
  SETTINGS("settings"),
  ;

  companion object {
    fun fromRaw(rawValue: String?): AppShellTab? = entries.firstOrNull { tab ->
      tab.name.equals(rawValue, ignoreCase = true) || tab.routeKey.equals(rawValue, ignoreCase = true)
    }
  }
}

enum class SettingsSubpage(
  val routeKey: String,
) {
  HOME("home"),
  NOTIFICATIONS_BACKGROUND("notifications_background"),
  NOTIFICATION_CHANNELS("notification_channels"),
  WORKSPACE("workspace"),
  LLM("llm"),
  MCP("mcp"),
  PRIVACY("privacy"),
  SAFETY("safety"),
  PERSONALIZATION("personalization"),
  AGENTS("agents"),
  ABOUT("about"),
  ;

  companion object {
    fun fromRaw(rawValue: String?): SettingsSubpage? = entries.firstOrNull { subpage ->
      subpage.name.equals(rawValue, ignoreCase = true) ||
        subpage.routeKey.equals(rawValue, ignoreCase = true)
    }
  }
}

data class AppShellDestination(
  val selectedTab: AppShellTab,
  val settingsSubpage: SettingsSubpage = SettingsSubpage.HOME,
) {
  companion object {
    fun default(): AppShellDestination = AppShellDestination(
      selectedTab = AppShellTab.CHAT,
      settingsSubpage = SettingsSubpage.HOME,
    )

    fun fromRaw(
      selectedTabRaw: String?,
      settingsSubpageRaw: String?,
    ): AppShellDestination {
      val tab = AppShellTab.fromRaw(selectedTabRaw) ?: return default()
      val settingsSubpage = SettingsSubpage.fromRaw(settingsSubpageRaw) ?: SettingsSubpage.HOME
      return AppShellDestination(
        selectedTab = tab,
        settingsSubpage = settingsSubpage,
      )
    }
  }
}

object AppShellNavigationExtras {
  const val EXTRA_START_TAB = "com.opencray.app.AppShellActivity.extra.START_TAB"
  const val EXTRA_START_SETTINGS_PAGE = "com.opencray.app.AppShellActivity.extra.START_SETTINGS_PAGE"
  const val EXTRA_CHAT_SCENARIO = "com.opencray.app.AppShellActivity.extra.CHAT_SCENARIO"
  const val EXTRA_FILES_SCENARIO = "com.opencray.app.AppShellActivity.extra.FILES_SCENARIO"
  const val EXTRA_SAFETY_SCENARIO = "com.opencray.app.AppShellActivity.extra.SAFETY_SCENARIO"
}
