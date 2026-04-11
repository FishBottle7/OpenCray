package com.opencray.app

internal data class OpenCrayRuntimeServiceHost(
  val dependencies: OpenCrayRuntimeContextDependencies,
  val runtimeAccess: OpenCrayRuntimeOwnerAccess,
  val serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  val serviceWorkStateTracker: RuntimeServiceWorkStateTracker,
  val bootstrapResult: RuntimeServiceBootstrapResult = RuntimeServiceBootstrapResult(
    scannedSessionIds = emptyList(),
    resumedSessionIds = emptyList(),
    repairedSessionIds = emptyList(),
  ),
  val scheduledTaskSpecStore: ScheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
  val scheduledTaskRunRecordStore: ScheduledTaskRunRecordStore =
    inMemoryScheduledTaskRunRecordStoreFactory().create(),
  val scheduledTaskTriggerSyncStateStore: ScheduledTaskTriggerSyncStateStore =
    inMemoryScheduledTaskTriggerSyncStateStoreFactory().create(),
  val scheduledTriggerRegistrar: ScheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
) {
  fun resumeInterruptedRuns(): RuntimeServiceInterruptedRunRepairResult =
    resumeInterruptedRuntimeServiceRuns(
      chatSessionStore = dependencies.chatSessionStore,
      runtimeAccess = runtimeAccess,
    )

  fun approvePendingApproval(
    taskIdOrRunId: String,
    nowEpochMs: Long = System.currentTimeMillis(),
  ) {
    runtimeServiceApprovalDecisionAccess(
      dependencies = dependencies,
      runtimeAccess = runtimeAccess,
      nowEpochMsProvider = { nowEpochMs },
    ).approve(taskIdOrRunId)
  }

  fun approvePendingApprovalForSession(
    taskIdOrRunId: String,
    nowEpochMs: Long = System.currentTimeMillis(),
  ) {
    runtimeServiceApprovalDecisionAccess(
      dependencies = dependencies,
      runtimeAccess = runtimeAccess,
      nowEpochMsProvider = { nowEpochMs },
    ).approveForSession(taskIdOrRunId)
  }

  fun rejectPendingApproval(
    taskIdOrRunId: String,
    nowEpochMs: Long = System.currentTimeMillis(),
  ) {
    runtimeServiceApprovalDecisionAccess(
      dependencies = dependencies,
      runtimeAccess = runtimeAccess,
      nowEpochMsProvider = { nowEpochMs },
    ).reject(taskIdOrRunId)
  }
}

internal fun OpenCrayRuntimeServiceHost.toRuntimeServiceBootstrapAssembly():
  RuntimeServiceBootstrapAssembly = RuntimeServiceBootstrapAssembly(
  dependencies = dependencies,
  runtimeAccess = runtimeAccess,
  serviceLifecycle = serviceLifecycle,
  bootstrapResult = bootstrapResult,
  serviceWorkStateTracker = serviceWorkStateTracker,
  scheduledTaskSpecStore = scheduledTaskSpecStore,
  scheduledTaskRunRecordStore = scheduledTaskRunRecordStore,
  scheduledTaskTriggerSyncStateStore = scheduledTaskTriggerSyncStateStore,
  scheduledTriggerRegistrar = scheduledTriggerRegistrar,
)

internal fun OpenCrayRuntimeServiceHost.toRuntimeServiceBootstrapState():
  RuntimeServiceBootstrapState = toRuntimeServiceBootstrapAssembly().toRuntimeServiceBootstrapState()

internal object OpenCrayRuntimeServiceHostRegistry {
  private var instance: OpenCrayRuntimeServiceHost? = null

  fun peek(): OpenCrayRuntimeServiceHost? = instance

  fun clearForTest() {
    instance = null
  }

  fun setForTest(host: OpenCrayRuntimeServiceHost?) {
    instance = host
  }
}
