package com.opencray.app

import android.app.Application
import android.os.Build
import android.content.Context
import com.opencray.runtime.OpenCrayDocumentRuntimeEnvironment
import java.io.File
import java.nio.charset.StandardCharsets

class OpenCrayApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    if (!shouldBootstrapOpenCrayApplication(packageName, currentProcessNameOrNull())) {
      return
    }
    bootstrapOpenCrayApplication(this)
  }
}

internal fun shouldBootstrapOpenCrayApplication(
  packageName: String,
  processName: String?,
): Boolean {
  val normalizedPackageName = packageName.trim()
  if (normalizedPackageName.isBlank()) {
    return true
  }
  val normalizedProcessName = processName?.trim()?.takeIf(String::isNotBlank) ?: return true
  return normalizedProcessName == normalizedPackageName
}

internal fun currentProcessNameOrNull(): String? {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    return Application.getProcessName().trim().takeIf(String::isNotBlank)
  }
  return runCatching {
    File("/proc/self/cmdline")
      .readBytes()
      .toString(StandardCharsets.UTF_8)
      .trim { character -> character <= ' ' || character == '\u0000' }
      .takeIf(String::isNotBlank)
  }.getOrNull()
}

internal fun bootstrapOpenCrayApplication(
  application: Application,
  registerVisibility: (Application) -> Unit = AppVisibilityMonitor::register,
  initializeRuntimeDocumentSupport: (Context) -> Unit = { context ->
    OpenCrayDocumentRuntimeEnvironment.initialize(context)
  },
  seedBundledSkills: (Context) -> Unit = { context ->
    BuiltinSkillsSeeder.fromContext(context).seedBundledSkillsIfNeeded()
  },
  resyncEnabledSchedules: (Context) -> Unit = ::resyncEnabledScheduledTasksFromContext,
  enqueueRepair: (Context, String) -> Unit = { context, reason ->
    WorkManagerScheduledWorkScheduler.fromContext(context).enqueueRepair(reason)
  },
) {
  registerVisibility(application)
  bootstrapOpenCrayRuntimeProcessSupport(
    context = application,
    initializeRuntimeDocumentSupport = initializeRuntimeDocumentSupport,
    seedBundledSkills = seedBundledSkills,
  )
  runCatching {
    resyncEnabledSchedules(application)
  }
  enqueueRepair(application, ScheduledTaskRepairReasons.APP_START)
}

internal fun bootstrapOpenCrayRuntimeProcessSupport(
  context: Context,
  initializeRuntimeDocumentSupport: (Context) -> Unit = { runtimeContext ->
    OpenCrayDocumentRuntimeEnvironment.initialize(runtimeContext)
  },
  seedBundledSkills: (Context) -> Unit = { runtimeContext ->
    BuiltinSkillsSeeder.fromContext(runtimeContext).seedBundledSkillsIfNeeded()
  },
) {
  initializeRuntimeDocumentSupport(context)
  seedBundledSkills(context)
}
