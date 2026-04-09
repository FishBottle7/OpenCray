package com.opencray.app

import android.content.Context

internal data class RuntimeServiceBootstrapParts(
  val dependencies: OpenCrayRuntimeContextDependencies,
  val runtimeAccess: OpenCrayRuntimeOwnerAccess,
  val scheduledTaskSpecStore: ScheduledTaskSpecStore,
  val scheduledTaskRunRecordStore: ScheduledTaskRunRecordStore,
  val scheduledTaskTriggerSyncStateStore: ScheduledTaskTriggerSyncStateStore,
  val scheduledTriggerRegistrar: ScheduledTriggerRegistrar,
)

internal fun interface RuntimeServiceBootstrapFactory {
  fun create(
    appContext: Context,
    runtimeOwnerAccessFactory: OpenCrayRuntimeOwnerAccessFactory,
  ): RuntimeServiceBootstrapParts
}

internal object DefaultRuntimeServiceBootstrapFactory :
  RuntimeServiceBootstrapFactory {
  override fun create(
    appContext: Context,
    runtimeOwnerAccessFactory: OpenCrayRuntimeOwnerAccessFactory,
  ): RuntimeServiceBootstrapParts {
    val dependencies = loadOpenCrayRuntimeContextDependencies(appContext)
    return RuntimeServiceBootstrapParts(
      dependencies = dependencies,
      runtimeAccess = runtimeOwnerAccessFactory.create(dependencies),
      scheduledTaskSpecStore = FileBackedScheduledTaskSpecStoreFactory
        .fromContext(appContext)
        .create(),
      scheduledTaskRunRecordStore = FileBackedScheduledTaskRunRecordStoreFactory
        .fromContext(appContext)
        .create(),
      scheduledTaskTriggerSyncStateStore = FileBackedScheduledTaskTriggerSyncStateStoreFactory
        .fromContext(appContext)
        .create(),
      scheduledTriggerRegistrar = DefaultScheduledTriggerRegistrar(
        alarmScheduler = AlarmManagerScheduledAlarmScheduler.fromContext(appContext),
        workScheduler = WorkManagerScheduledWorkScheduler.fromContext(appContext),
      ),
    )
  }
}
