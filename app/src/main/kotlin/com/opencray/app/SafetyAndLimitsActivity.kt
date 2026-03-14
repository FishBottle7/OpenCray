package com.opencray.app

import android.os.Bundle

class SafetyAndLimitsActivity : LocalizedActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    startActivity(
      OpenCrayFlutterActivity.intent(
        this,
        OpenCrayFlutterActivity.Destination.SETTINGS_SAFETY,
      ),
    )
    finish()
  }
}
