package com.opencray.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellTab

class SkillsManagementActivity : LocalizedActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    startActivity(
      Intent(this, AppShellActivity::class.java).apply {
        putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.SKILLS.name)
      },
    )
    finish()
  }
}
