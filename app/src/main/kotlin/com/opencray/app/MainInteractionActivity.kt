package com.opencray.app

import android.os.Bundle

class MainInteractionActivity : LocalizedActivity() {
  companion object {
    const val EXTRA_SCENARIO = "com.opencray.app.MainInteractionActivity.extra.SCENARIO"
    const val SCENARIO_DEFAULT_APPROVAL = "default_approval"
    const val SCENARIO_DENIED_POLICY = "denied_policy"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    startActivity(
      OpenCrayFlutterActivity.intent(
        this,
        OpenCrayFlutterActivity.Destination.CHAT,
      ).apply {
        if (intent.hasExtra(EXTRA_SCENARIO)) {
          putExtra(EXTRA_SCENARIO, intent.getStringExtra(EXTRA_SCENARIO))
        }
      },
    )
    finish()
  }
}
