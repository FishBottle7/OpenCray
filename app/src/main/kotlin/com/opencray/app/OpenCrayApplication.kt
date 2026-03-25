package com.opencray.app

import android.app.Application

class OpenCrayApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    AppVisibilityMonitor.register(this)
    BuiltinSkillsSeeder.fromContext(this).seedBundledSkillsIfNeeded()
    WorkManagerScheduledWorkScheduler.fromContext(this).enqueueRepair(
      ScheduledTaskRepairReasons.APP_START,
    )
    OpenCrayAgentRuntimeService.ensureStarted(this)
  }
}
