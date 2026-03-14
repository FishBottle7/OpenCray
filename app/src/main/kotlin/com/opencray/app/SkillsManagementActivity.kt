package com.opencray.app

import android.os.Bundle

class SkillsManagementActivity : LocalizedActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    startActivity(OpenCrayFlutterActivity.intent(this, OpenCrayFlutterActivity.Destination.SKILLS))
    finish()
  }
}
