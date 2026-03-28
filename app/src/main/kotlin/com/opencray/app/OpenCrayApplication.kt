package com.opencray.app

import android.app.Application
import android.content.Context

class OpenCrayApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    bootstrapOpenCrayApplication(this)
  }
}

internal fun bootstrapOpenCrayApplication(
  application: Application,
  registerVisibility: (Application) -> Unit = AppVisibilityMonitor::register,
  seedBundledSkills: (Context) -> Unit = { context ->
    BuiltinSkillsSeeder.fromContext(context).seedBundledSkillsIfNeeded()
  },
  resyncEnabledSchedules: (Context) -> Unit = ::resyncEnabledScheduledTasksFromContext,
  enqueueRepair: (Context, String) -> Unit = { context, reason ->
    WorkManagerScheduledWorkScheduler.fromContext(context).enqueueRepair(reason)
  },
  ensureRuntime: (Context) -> Unit = { context ->
    OpenCrayAgentRuntimeService.ensureStarted(context)
  },
) {
  registerVisibility(application)
  seedBundledSkills(application)
  runCatching {
    resyncEnabledSchedules(application)
  }
  enqueueRepair(application, ScheduledTaskRepairReasons.APP_START)
  ensureRuntime(application)
}
