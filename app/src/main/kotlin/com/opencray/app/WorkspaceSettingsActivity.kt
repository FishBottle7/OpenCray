package com.opencray.app

import android.os.Bundle

class WorkspaceSettingsActivity : LocalizedActivity() {
  companion object {
    const val EXTRA_SCENARIO = "com.opencray.app.WorkspaceSettingsActivity.extra.SCENARIO"
    const val SCENARIO_NO_GRANT: String = "no_grant"
    const val SCENARIO_ACTIVE_GRANT: String = "active_grant"
    const val SCENARIO_REVOKED_GRANT: String = "revoked_grant"
    const val SCENARIO_OUTSIDE_ROOT_DENIAL: String = "outside_root_denial"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    startActivity(
      OpenCrayFlutterActivity.intent(
        this,
        OpenCrayFlutterActivity.Destination.SETTINGS_WORKSPACE,
      ).apply {
        if (intent.hasExtra(EXTRA_SCENARIO)) {
          putExtra(EXTRA_SCENARIO, intent.getStringExtra(EXTRA_SCENARIO))
        }
      },
    )
    finish()
  }
}
