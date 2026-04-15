package com.opencray.app

import android.content.Context

internal data class RuntimeServiceBootstrapAssembly(
  val dependencies: OpenCrayRuntimeContextDependencies,
  val runtimeAccess: OpenCrayRuntimeOwnerAccess,
  val serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  val serviceWorkStateTracker: RuntimeServiceWorkStateTracker,
  val scheduledTaskSpecStore: ScheduledTaskSpecStore,
  val scheduledTaskRunRecordStore: ScheduledTaskRunRecordStore,
  val scheduledTaskTriggerSyncStateStore: ScheduledTaskTriggerSyncStateStore,
  val scheduledTriggerRegistrar: ScheduledTriggerRegistrar,
)

internal data class RuntimeServiceBootstrapResult(
  val scannedSessionIds: List<String>,
  val resumedSessionIds: List<String>,
  val repairedSessionIds: List<String>,
)

internal data class RuntimeServiceInterruptedRunRepairResult(
  val scannedSessionIds: List<String>,
  val resumedSessionIds: List<String>,
  val repairedSessionIds: List<String>,
)

internal fun RuntimeServiceBootstrapAssembly.toRuntimeServiceBootstrapState():
  RuntimeServiceBootstrapState = RuntimeServiceBootstrapState(
  gatewayDependencies = toGatewayBundleDependencies(),
  executionCoordinatorDependencies = toExecutionCoordinatorDependencies(),
  wakeCommandDispatcherDependencies = toWakeCommandDispatcherDependencies(),
  binderEndpointDependencies = toBinderEndpointDependencies(),
)

internal fun createRuntimeServiceBootstrapAssembly(
  appContext: Context,
  serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  runtimeOwnerAccessFactory: OpenCrayRuntimeOwnerAccessFactory =
    DefaultOpenCrayRuntimeOwnerAccessFactory,
  bootstrapFactory: RuntimeServiceBootstrapFactory =
    DefaultRuntimeServiceBootstrapFactory,
): RuntimeServiceBootstrapAssembly {
  val bootstrap = bootstrapFactory.create(
    appContext = appContext,
    runtimeOwnerAccessFactory = runtimeOwnerAccessFactory,
  )
  bootstrapRuntimeServiceSessions(
    chatSessionStore = bootstrap.dependencies.chatSessionStore,
    runtimeAccess = bootstrap.runtimeAccess,
  )
  resyncEnabledScheduledTasks(
    specStore = bootstrap.scheduledTaskSpecStore,
    triggerRegistrar = bootstrap.scheduledTriggerRegistrar,
    triggerSyncStateStore = bootstrap.scheduledTaskTriggerSyncStateStore,
  )
  val serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
    workSummaryProvider = bootstrap.runtimeAccess.hostAccess::activeWorkSummary,
  )
  bootstrap.runtimeAccess.hostAccess.observe(
    object : AgentSessionRuntimeListener {
      override fun onTaskStarted(sessionId: String, task: com.opencray.core.contracts.AgentTask) {
        serviceWorkStateTracker.refresh()
      }

      override fun onTaskFinished(
        sessionId: String,
        task: com.opencray.core.contracts.AgentTask,
        result: com.opencray.core.contracts.ExecutionResult,
      ) {
        serviceWorkStateTracker.refresh()
      }

      override fun onRunEvent(
        sessionId: String,
        task: com.opencray.core.contracts.AgentTask,
        event: com.opencray.runtime.OpenCrayAgentRunEvent,
      ) {
        serviceWorkStateTracker.refresh()
      }
    },
  )
  serviceWorkStateTracker.refresh()
  return RuntimeServiceBootstrapAssembly(
    dependencies = bootstrap.dependencies,
    runtimeAccess = bootstrap.runtimeAccess,
    serviceLifecycle = serviceLifecycle,
    serviceWorkStateTracker = serviceWorkStateTracker,
    scheduledTaskSpecStore = bootstrap.scheduledTaskSpecStore,
    scheduledTaskRunRecordStore = bootstrap.scheduledTaskRunRecordStore,
    scheduledTaskTriggerSyncStateStore = bootstrap.scheduledTaskTriggerSyncStateStore,
    scheduledTriggerRegistrar = bootstrap.scheduledTriggerRegistrar,
  )
}

internal fun createRuntimeServiceBootstrapState(
  appContext: Context,
  serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  runtimeOwnerAccessFactory: OpenCrayRuntimeOwnerAccessFactory =
    DefaultOpenCrayRuntimeOwnerAccessFactory,
  bootstrapFactory: RuntimeServiceBootstrapFactory =
    DefaultRuntimeServiceBootstrapFactory,
): RuntimeServiceBootstrapState = createRuntimeServiceBootstrapAssembly(
  appContext = appContext,
  serviceLifecycle = serviceLifecycle,
  runtimeOwnerAccessFactory = runtimeOwnerAccessFactory,
  bootstrapFactory = bootstrapFactory,
).toRuntimeServiceBootstrapState()

private fun RuntimeServiceBootstrapAssembly.toGatewayBundleDependencies():
  RuntimeServiceGatewayBundleDependencies {
  val approvalDecisionAccess = runtimeServiceApprovalDecisionAccess(
    dependencies = dependencies,
    runtimeAccess = runtimeAccess,
  )
  return RuntimeServiceGatewayBundleDependencies(
    runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
    runtimeHostAccess = runtimeAccess.hostAccess,
    runtimeReplayAccess = runtimeAccess.replayAccess,
    serviceLifecycle = serviceLifecycle,
    serviceWorkStateProvider = serviceWorkStateTracker::currentState,
    safetySettingsFacade = dependencies.safetySettingsFacade,
    workspaceRootProvider = dependencies.workspaceRootProvider,
    approvedReadRootsProvider = dependencies.approvedReadRootsProvider,
    approvePendingApproval = approvalDecisionAccess::approve,
    approvePendingApprovalForSession = approvalDecisionAccess::approveForSession,
    rejectPendingApproval = approvalDecisionAccess::reject,
    onDeviceWarmupPlanner = runtimeAccess.onDeviceWarmupPlanner,
  )
}

private fun RuntimeServiceBootstrapAssembly.toExecutionCoordinatorDependencies():
  RuntimeServiceExecutionCoordinatorDependencies = RuntimeServiceExecutionCoordinatorDependencies(
  runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
  runtimeHostAccess = runtimeAccess.hostAccess,
  serviceLifecycle = serviceLifecycle,
  serviceWorkStateTracker = serviceWorkStateTracker,
  localizedContext = dependencies.localizedContext,
  chatSessionStore = dependencies.chatSessionStore,
  scheduledTaskSpecStore = scheduledTaskSpecStore,
  scheduledTaskRunRecordStore = scheduledTaskRunRecordStore,
)

private fun RuntimeServiceBootstrapAssembly.toBridgeSnapshotDependencies():
  RuntimeServiceBridgeSnapshotDependencies = RuntimeServiceBridgeSnapshotDependencies(
  dependencies = dependencies,
  runtimeAccess = runtimeAccess,
  serviceLifecycle = serviceLifecycle,
)

private fun RuntimeServiceBootstrapAssembly.toScheduledTaskDispatcherDependencies():
  ScheduledTaskDispatcherDependencies = ScheduledTaskDispatcherDependencies(
  hostAccess = runtimeAccess.hostAccess,
  chatSessionStore = dependencies.chatSessionStore,
  safetySettingsFacade = dependencies.safetySettingsFacade,
  approvedReadRootsProvider = dependencies.approvedReadRootsProvider,
  lifecycleDescriptor = runtimeAccess.lifecycleDescriptor,
  localizedContext = dependencies.localizedContext,
  assistantPlaceholderTextProvider = {
    dependencies.localizedContext.getText(org.opencray.app.R.string.chat_agent_thinking).toString()
  },
  specStore = scheduledTaskSpecStore,
  runRecordStore = scheduledTaskRunRecordStore,
  triggerRegistrar = scheduledTriggerRegistrar,
)

private fun RuntimeServiceBootstrapAssembly.toScheduledTaskRepairDependencies(
  scheduledTaskDispatcherDependencies: ScheduledTaskDispatcherDependencies =
    toScheduledTaskDispatcherDependencies(),
): ScheduledTaskRepairDependencies = ScheduledTaskRepairDependencies(
  scheduledTaskDispatcherDependencies = scheduledTaskDispatcherDependencies,
  specStore = scheduledTaskSpecStore,
  triggerRegistrar = scheduledTriggerRegistrar,
  triggerSyncStateStore = scheduledTaskTriggerSyncStateStore,
)

private fun RuntimeServiceBootstrapAssembly.toWakeCommandDispatcherDependencies():
  RuntimeServiceWakeCommandDispatcherDependencies {
  val scheduledTaskDispatcherDependencies = toScheduledTaskDispatcherDependencies()
  val approvalDecisionAccess = runtimeServiceApprovalDecisionAccess(
    dependencies = dependencies,
    runtimeAccess = runtimeAccess,
  )
  return RuntimeServiceWakeCommandDispatcherDependencies(
    scheduledTaskDispatcherDependencies = scheduledTaskDispatcherDependencies,
    scheduledTaskRepairDependencies = toScheduledTaskRepairDependencies(
      scheduledTaskDispatcherDependencies = scheduledTaskDispatcherDependencies,
    ),
    resumeInterruptedRuns = {
      resumeInterruptedRuntimeServiceRuns(
        chatSessionStore = dependencies.chatSessionStore,
        runtimeAccess = runtimeAccess,
      )
    },
    approvePendingApproval = approvalDecisionAccess::approve,
    rejectPendingApproval = approvalDecisionAccess::reject,
    refreshServiceWorkState = serviceWorkStateTracker::refresh,
  )
}

private fun RuntimeServiceBootstrapAssembly.toBinderEndpointDependencies():
  RuntimeServiceBinderEndpointDependencies {
  val approvalDecisionAccess = runtimeServiceApprovalDecisionAccess(
    dependencies = dependencies,
    runtimeAccess = runtimeAccess,
  )
  return RuntimeServiceBinderEndpointDependencies(
    bridgeSnapshotDependencies = toBridgeSnapshotDependencies(),
    approvePendingApproval = approvalDecisionAccess::approve,
    approvePendingApprovalForSession = approvalDecisionAccess::approveForSession,
    rejectPendingApproval = approvalDecisionAccess::reject,
    refreshServiceWorkState = serviceWorkStateTracker::refresh,
  )
}

private fun submitRecoverableSubAgentTasksForSession(
  session: OpenCrayRuntimeSessionAccess,
) {
  session.ensureRecoverableDetachedSubAgentTasks()
}

internal fun bootstrapRuntimeServiceSessions(
  chatSessionStore: ChatSessionLocalStore,
  runtimeAccess: OpenCrayRuntimeOwnerAccess,
): RuntimeServiceBootstrapResult {
  val state = chatSessionStore.loadState()
  val knownSessionIds = knownChatSessionIds(chatSessionStore)
  val resumedSessionIds = mutableListOf<String>()
  val repairedSessionIds = mutableListOf<String>()

  knownSessionIds.forEach { sessionId ->
    val session = runtimeAccess.hostAccess.session(sessionId)
    val shouldResume = sessionId == state.activeSession.sessionId ||
      session.hasPendingWork() ||
      session.hasLiveManagedProcesses() ||
      session.hasLiveSubAgentWork()
    if (!shouldResume) {
      return@forEach
    }
    session.resume()
    resumedSessionIds += sessionId
    val runs = session.listRuns()
    if (runs.isNotEmpty()) {
      runtimeAccess.replayAccess.terminalReplayRepairer(sessionId, runs)
      repairedSessionIds += sessionId
    }
    submitRecoverableSubAgentTasksForSession(
      session = session,
    )
  }

  return RuntimeServiceBootstrapResult(
    scannedSessionIds = knownSessionIds,
    resumedSessionIds = resumedSessionIds,
    repairedSessionIds = repairedSessionIds,
  )
}

internal fun resumeInterruptedRuntimeServiceRuns(
  chatSessionStore: ChatSessionLocalStore,
  runtimeAccess: OpenCrayRuntimeOwnerAccess,
): RuntimeServiceInterruptedRunRepairResult {
  val knownSessionIds = knownChatSessionIds(chatSessionStore)
  val resumedSessionIds = mutableListOf<String>()
  val repairedSessionIds = mutableListOf<String>()

  knownSessionIds.forEach { sessionId ->
    val session = runtimeAccess.hostAccess.session(sessionId)
    val runs = session.listRuns()
    val shouldResume = runs.any(AgentRunSnapshot::isActive) || session.hasLiveSubAgentWork()
    if (!shouldResume) {
      return@forEach
    }
    session.resume()
    resumedSessionIds += sessionId
    val repairedRuns = session.listRuns()
    if (repairedRuns.isNotEmpty()) {
      runtimeAccess.replayAccess.terminalReplayRepairer(sessionId, repairedRuns)
      repairedSessionIds += sessionId
    }
    submitRecoverableSubAgentTasksForSession(
      session = session,
    )
  }

  return RuntimeServiceInterruptedRunRepairResult(
    scannedSessionIds = knownSessionIds,
    resumedSessionIds = resumedSessionIds,
    repairedSessionIds = repairedSessionIds,
  )
}
