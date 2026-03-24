package com.opencray.app

import android.app.Application

class OpenCrayApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    BuiltinSkillsSeeder.fromContext(this).seedBundledSkillsIfNeeded()
    OpenCrayAgentRuntimeService.ensureStarted(this)
  }
}
