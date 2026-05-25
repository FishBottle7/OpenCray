package com.opencray.app

import android.content.Context

internal data class RuntimeServiceBootstrapParts(
  val scheduledTaskSpecStore: ScheduledTaskSpecStore,
  val scheduledTaskRunRecordStore: ScheduledTaskRunRecordStore,
  val scheduledTaskTriggerSyncStateStore: ScheduledTaskTriggerSyncStateStore,
  val scheduledTriggerRegistrar: ScheduledTriggerRegistrar,
)

internal fun interface RuntimeServiceBootstrapFactory {
  fun create(
    appContext: Context,
  ): RuntimeServiceBootstrapParts
}

internal object DefaultRuntimeServiceBootstrapFactory :
  RuntimeServiceBootstrapFactory {
  override fun create(
    appContext: Context,
  ): RuntimeServiceBootstrapParts {
    return RuntimeServiceBootstrapParts(
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
