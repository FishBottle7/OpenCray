package com.opencray.app

import android.app.Activity
import android.content.Context

open class LocalizedActivity : Activity() {
  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(OpenCrayLocaleManager.wrap(newBase))
  }
}
