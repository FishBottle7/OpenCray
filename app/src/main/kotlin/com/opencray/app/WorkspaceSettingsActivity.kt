package com.opencray.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellTab
import com.opencray.app.shell.SettingsSubpage

class WorkspaceSettingsActivity : Activity() {
  companion object {
    const val EXTRA_SCENARIO = "com.opencray.app.WorkspaceSettingsActivity.extra.SCENARIO"
    const val SCENARIO_NO_GRANT: String = FILES_WORKBENCH_SCENARIO_NO_GRANT
    const val SCENARIO_ACTIVE_GRANT: String = FILES_WORKBENCH_SCENARIO_ACTIVE_GRANT
    const val SCENARIO_REVOKED_GRANT: String = FILES_WORKBENCH_SCENARIO_REVOKED_GRANT
    const val SCENARIO_OUTSIDE_ROOT_DENIAL: String = FILES_WORKBENCH_SCENARIO_OUTSIDE_ROOT_DENIAL
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    startActivity(
      Intent(this, AppShellActivity::class.java).apply {
        putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.SETTINGS.name)
        putExtra(AppShellNavigationExtras.EXTRA_START_SETTINGS_PAGE, SettingsSubpage.WORKSPACE.name)
        if (intent.hasExtra(EXTRA_SCENARIO)) {
          putExtra(
            AppShellNavigationExtras.EXTRA_FILES_SCENARIO,
            intent.getStringExtra(EXTRA_SCENARIO),
          )
        }
      },
    )
    finish()
  }
}
