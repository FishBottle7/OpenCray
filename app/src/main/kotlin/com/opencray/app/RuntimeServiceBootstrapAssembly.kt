package com.opencray.app

import android.content.Context
import java.nio.file.Path

internal data class RuntimeServiceBootstrapContext(
  val localizedContext: Context,
  val chatSessionStore: ChatSessionLocalStore,
  val safetySettingsFacade: com.opencray.app.facade.safety.SafetySettingsFacade,
  val workspaceRootProvider: () -> Path,
  val approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot,
  val runtimeServiceAccessGateway: RuntimeServiceAccessGateway,
  val chatRuntimeWriteTargetResolverFactory: ChatRuntimeWriteTargetResolverFactory,
)

internal fun runtimeServiceBootstrapContext(
  dependencies: OpenCrayRuntimeContextDependencies,
): RuntimeServiceBootstrapContext = RuntimeServiceBootstrapContext(
  localizedContext = dependencies.localizedContext,
  chatSessionStore = dependencies.chatSessionStore,
  safetySettingsFacade = dependencies.safetySettingsFacade,
  workspaceRootProvider = dependencies.workspaceRootProvider,
  approvedReadRootsProvider = dependencies.approvedReadRootsProvider,
  runtimeServiceAccessGateway = dependencies.runtimeServiceAccessGateway,
  chatRuntimeWriteTargetResolverFactory = dependencies.chatRuntimeWriteTargetResolverFactory,
)

internal data class RuntimeServiceBootstrapAssembly(
  val bootstrapContext: RuntimeServiceBootstrapContext,
  val retainedOwnerState: RuntimeServiceRetainedOwnerState,
  val projectionCoordinator: RuntimeServiceProjectionCoordinator,
  val transportCoordinator: RuntimeServiceTransportCoordinator,
  val retainedShellControl: RuntimeServiceRetainedShellControl,
  val runtimeTarget: RuntimeServiceTarget = RuntimeServiceTarget.INTERACTIVE,
  val localRuntimeServerStateProvider: () -> LocalRuntimeServerState? = { null },
  val runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor? = null,
  val bootstrapResult: RuntimeServiceBootstrapResult,
  val serviceWorkStateTracker: RuntimeServiceWorkStateTracker,
  val scheduledTaskSpecStore: ScheduledTaskSpecStore,
  val scheduledTaskRunRecordStore: ScheduledTaskRunRecordStore,
  val scheduledTaskTriggerSyncStateStore: ScheduledTaskTriggerSyncStateStore,
  val scheduledTriggerRegistrar: ScheduledTriggerRegistrar,
  private val runtimeOwnerRebinder: (RuntimeOwnerBootstrap) -> Unit = {},
  private val disposeHandler: () -> Unit = {},
)

{
  private val disposeLock = Any()
  private var disposed: Boolean = false

  val runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor
    get() = retainedOwnerState.currentRuntimeServicePort().lifecycleDescriptor

  val runtimeServicePort: RuntimeServicePort
    get() = retainedOwnerState.currentRuntimeServicePort()

  fun replaceRuntimeOwner(): RuntimeOwnerBootstrap {
    val nextBootstrap = retainedOwnerState.replaceRuntimeOwner()
    runtimeOwnerRebinder(nextBootstrap)
    return nextBootstrap
  }

  fun dispose() {
    val handler = synchronized(disposeLock) {
      if (disposed) {
        null
      } else {
        disposed = true
        disposeHandler
      }
    } ?: return
    handler()
  }
}

internal data class RuntimeServiceBootstrapResult(
  val scannedSessionIds: List<String>,
  val resumedSessionIds: List<String>,
  val repairedSessionIds: List<String>,
  val repairEvidenceBySession: Map<String, List<InterruptedRunRepairEvidence>> = emptyMap(),
)

internal data class RuntimeServiceInterruptedRunRepairResult(
  val scannedSessionIds: List<String>,
  val resumedSessionIds: List<String>,
  val repairedSessionIds: List<String>,
  val repairEvidenceBySession: Map<String, List<InterruptedRunRepairEvidence>> = emptyMap(),
)

internal fun RuntimeServiceBootstrapAssembly.toRuntimeServiceBootstrapState(
  serviceLifecycle: RuntimeServiceLifecycleDescriptor,
): RuntimeServiceBootstrapState {
  projectionCoordinator.bindServiceLifecycle(serviceLifecycle)
  return RuntimeServiceBootstrapState(
    gatewayDependencies = toGatewayBundleDependencies(serviceLifecycle),
    executionCoordinatorDependencies = toExecutionCoordinatorDependencies(),
    wakeCommandDispatcherDependencies = toWakeCommandDispatcherDependencies(),
    binderEndpointDependencies = toBinderEndpointDependencies(serviceLifecycle),
    retainedShellControl = retainedShellControl,
    transportCoordinator = transportCoordinator,
  )
}

internal fun createRuntimeServiceBootstrapAssembly(
  appContext: Context,
  bootstrapContext: RuntimeServiceBootstrapContext,
  retainedOwnerState: RuntimeServiceRetainedOwnerState,
  runtimeTarget: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor? = null,
  localRuntimeServerStateProvider: (() -> LocalRuntimeServerState?)? = null,
  retainedShellControlFactory: (Context) -> RuntimeServiceRetainedShellControl =
    ::createRuntimeServiceRetainedShellControl,
  bootstrapFactory: RuntimeServiceBootstrapFactory =
    DefaultRuntimeServiceBootstrapFactory,
): RuntimeServiceBootstrapAssembly {
  val initialRuntimePort = retainedOwnerState.currentRuntimeServicePort()
  val bootstrap = bootstrapFactory.create(
    appContext = appContext,
  )
  val bootstrapResult = bootstrapRuntimeServiceSessions(
    chatSessionStore = bootstrapContext.chatSessionStore,
    runtimeSessionDirectoryAccess = initialRuntimePort.notificationHostAccess,
    runtimeReplayAccess = initialRuntimePort.replayAccess,
    snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(appContext),
    promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory.fromContext(appContext),
    subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory.fromContext(appContext),
    runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory.fromContext(appContext),
    runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory.fromContext(appContext),
  )
  resyncEnabledScheduledTasks(
    specStore = bootstrap.scheduledTaskSpecStore,
    triggerRegistrar = bootstrap.scheduledTriggerRegistrar,
    triggerSyncStateStore = bootstrap.scheduledTaskTriggerSyncStateStore,
  )
  val serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
    workSummaryProvider = {
      retainedOwnerState.currentRuntimeServicePort().ownerObservationAccess.activeWorkSummary()
    },
  )
  val transportCoordinator = DefaultRuntimeServiceTransportCoordinator(
    runtimeTarget = runtimeTarget,
    initialLocalRuntimeServerStateProvider = localRuntimeServerStateProvider
      ?: { defaultLocalRuntimeServerState(runtimeTarget) },
  )
  val projectionCoordinator = DefaultRuntimeServiceProjectionCoordinator(
    runtimeTarget = runtimeTarget,
    localRuntimeServerStateProvider = transportCoordinator::currentLocalRuntimeServerState,
    runtimeControllerLifecycle = runtimeControllerLifecycle,
    runtimeOwnerLifecycle = initialRuntimePort.lifecycleDescriptor,
    ownerObservationAccess = initialRuntimePort.ownerObservationAccess,
    notificationHostAccess = initialRuntimePort.notificationHostAccess,
    serviceWorkStateTracker = serviceWorkStateTracker,
    appContext = appContext,
    localizedContext = bootstrapContext.localizedContext,
    chatSessionStore = bootstrapContext.chatSessionStore,
    scheduledTaskSpecStore = bootstrap.scheduledTaskSpecStore,
    scheduledTaskRunRecordStore = bootstrap.scheduledTaskRunRecordStore,
    runtimeServiceAccessGateway = bootstrapContext.runtimeServiceAccessGateway,
  )
  val retainedShellControl = retainedShellControlFactory(appContext)
  val ownerWorkStateObservationBinding = RuntimeServiceOwnerWorkStateObservationBinding(
    serviceWorkStateTracker = serviceWorkStateTracker,
  )
  ownerWorkStateObservationBinding.bind(initialRuntimePort.ownerObservationAccess)
  serviceWorkStateTracker.refresh()
  return RuntimeServiceBootstrapAssembly(
    bootstrapContext = bootstrapContext,
    retainedOwnerState = retainedOwnerState,
    projectionCoordinator = projectionCoordinator,
    transportCoordinator = transportCoordinator,
    retainedShellControl = retainedShellControl,
    runtimeTarget = runtimeTarget,
    localRuntimeServerStateProvider = transportCoordinator::currentLocalRuntimeServerState,
    runtimeControllerLifecycle = runtimeControllerLifecycle,
    bootstrapResult = bootstrapResult,
    serviceWorkStateTracker = serviceWorkStateTracker,
    scheduledTaskSpecStore = bootstrap.scheduledTaskSpecStore,
    scheduledTaskRunRecordStore = bootstrap.scheduledTaskRunRecordStore,
    scheduledTaskTriggerSyncStateStore = bootstrap.scheduledTaskTriggerSyncStateStore,
    scheduledTriggerRegistrar = bootstrap.scheduledTriggerRegistrar,
    runtimeOwnerRebinder = {
      val nextRuntimePort = retainedOwnerState.currentRuntimeServicePort()
      ownerWorkStateObservationBinding.bind(nextRuntimePort.ownerObservationAccess)
      projectionCoordinator.replaceRuntimeOwner(
        runtimeOwnerLifecycle = nextRuntimePort.lifecycleDescriptor,
        ownerObservationAccess = nextRuntimePort.ownerObservationAccess,
        notificationHostAccess = nextRuntimePort.notificationHostAccess,
      )
    },
    disposeHandler = {
      transportCoordinator.dispose()
      projectionCoordinator.dispose()
      retainedShellControl.runtimeForegroundController.onDestroy()
      retainedShellControl.keepAliveController.onDestroy()
      ownerWorkStateObservationBinding.dispose()
    },
  )
}

private class RuntimeServiceOwnerWorkStateObservationBinding(
  private val serviceWorkStateTracker: RuntimeServiceWorkStateTracker,
) {
  private val lock = Any()
  private var observationDisposer: (() -> Unit)? = null
  private val listener = object : AgentSessionRuntimeListener {
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
  }

  fun bind(ownerObservationAccess: RuntimeOwnerObservationAccess) {
    val previousDisposer = synchronized(lock) {
      observationDisposer.also {
        observationDisposer = ownerObservationAccess.observe(listener)
      }
    }
    previousDisposer?.invoke()
    serviceWorkStateTracker.refresh()
  }

  fun dispose() {
    val disposer = synchronized(lock) {
      observationDisposer.also {
        observationDisposer = null
      }
    }
    disposer?.invoke()
  }
}

private fun RuntimeServiceBootstrapAssembly.toGatewayBundleDependencies(
  serviceLifecycle: RuntimeServiceLifecycleDescriptor,
): RuntimeServiceGatewayBundleDependencies {
  val runtimeServicePort = retainedOwnerState.currentRuntimeServicePort()
  val approvalDecisionAccess = runtimeServiceApprovalDecisionAccess(
    dependencies = toApprovalDecisionDependencies(runtimeServicePort),
  )
  return RuntimeServiceGatewayBundleDependencies(
    runtimeControllerLifecycle = runtimeControllerLifecycle,
    runtimeServicePort = runtimeServicePort,
    localRuntimeServerStateProvider = localRuntimeServerStateProvider,
    serviceLifecycle = serviceLifecycle,
    serviceWorkStateProvider = serviceWorkStateTracker::currentState,
    safetySettingsFacade = bootstrapContext.safetySettingsFacade,
    workspaceRootProvider = bootstrapContext.workspaceRootProvider,
    approvedReadRootsProvider = bootstrapContext.approvedReadRootsProvider,
    approvalDecisionAccess = approvalDecisionAccess,
  )
}

private fun RuntimeServiceBootstrapAssembly.toExecutionCoordinatorDependencies(
):
  RuntimeServiceExecutionCoordinatorDependencies = RuntimeServiceExecutionCoordinatorDependencies(
  projectionCoordinator = projectionCoordinator,
  serviceWorkStateTracker = serviceWorkStateTracker,
)

private fun RuntimeServiceBootstrapAssembly.toBridgeSnapshotDependencies(
  serviceLifecycle: RuntimeServiceLifecycleDescriptor,
):
  RuntimeServiceBridgeSnapshotDependencies {
  val runtimeServicePort = retainedOwnerState.currentRuntimeServicePort()
  return RuntimeServiceBridgeSnapshotDependencies(
    runtimeOwnerLifecycle = runtimeServicePort.lifecycleDescriptor,
    runtimeOwnerWorkSummaryProvider = runtimeServicePort.ownerObservationAccess::activeWorkSummary,
    runtimeControllerLifecycle = runtimeControllerLifecycle,
    serviceLifecycle = serviceLifecycle,
    localRuntimeServerStateProvider = localRuntimeServerStateProvider,
  )
}

private fun RuntimeServiceBootstrapAssembly.toScheduledTaskDispatcherDependencies():
  ScheduledTaskDispatcherDependencies {
  val runtimeServicePort = retainedOwnerState.currentRuntimeServicePort()
  return ScheduledTaskDispatcherDependencies(
    hostAccess = runtimeServicePort.chatSubmissionHostAccess,
    chatSessionStore = bootstrapContext.chatSessionStore,
    safetySettingsFacade = bootstrapContext.safetySettingsFacade,
    approvedReadRootsProvider = bootstrapContext.approvedReadRootsProvider,
    lifecycleDescriptor = runtimeServicePort.lifecycleDescriptor,
    localizedContext = bootstrapContext.localizedContext,
    assistantPlaceholderTextProvider = {
      bootstrapContext.localizedContext.getText(org.opencray.app.R.string.chat_agent_thinking).toString()
    },
    specStore = scheduledTaskSpecStore,
    runRecordStore = scheduledTaskRunRecordStore,
    triggerRegistrar = scheduledTriggerRegistrar,
  )
}

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
  val runtimeServicePort = retainedOwnerState.currentRuntimeServicePort()
  val scheduledTaskDispatcherDependencies = toScheduledTaskDispatcherDependencies()
  val approvalDecisionAccess = runtimeServiceApprovalDecisionAccess(
    dependencies = toApprovalDecisionDependencies(runtimeServicePort),
  )
  return RuntimeServiceWakeCommandDispatcherDependencies(
    scheduledTaskDispatcherDependencies = scheduledTaskDispatcherDependencies,
    scheduledTaskRepairDependencies = toScheduledTaskRepairDependencies(
      scheduledTaskDispatcherDependencies = scheduledTaskDispatcherDependencies,
    ),
    resumeInterruptedRuns = {
      resumeInterruptedRuntimeServiceRuns(
        chatSessionStore = bootstrapContext.chatSessionStore,
        runtimeSessionDirectoryAccess = runtimeServicePort.notificationHostAccess,
        runtimeReplayAccess = runtimeServicePort.replayAccess,
        snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(
          bootstrapContext.localizedContext.applicationContext,
        ),
        promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory.fromContext(
          bootstrapContext.localizedContext.applicationContext,
        ),
        subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory.fromContext(
          bootstrapContext.localizedContext.applicationContext,
        ),
        runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory.fromContext(
          bootstrapContext.localizedContext.applicationContext,
        ),
        runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory.fromContext(
          bootstrapContext.localizedContext.applicationContext,
        ),
      )
    },
    approvalDecisionAccess = approvalDecisionAccess,
    refreshServiceWorkState = serviceWorkStateTracker::refresh,
  )
}

private fun RuntimeServiceBootstrapAssembly.toBinderEndpointDependencies(
  serviceLifecycle: RuntimeServiceLifecycleDescriptor,
):
  RuntimeServiceBinderEndpointDependencies {
  val runtimeServicePort = retainedOwnerState.currentRuntimeServicePort()
  val approvalDecisionAccess = runtimeServiceApprovalDecisionAccess(
    dependencies = toApprovalDecisionDependencies(runtimeServicePort),
  )
  return RuntimeServiceBinderEndpointDependencies(
    bridgeSnapshotDependencies = toBridgeSnapshotDependencies(serviceLifecycle),
    runtimeTarget = runtimeTarget,
    chatWriteTargetResolver = bootstrapContext.chatRuntimeWriteTargetResolverFactory.create(
      bootstrapContext.localizedContext.applicationContext,
    ),
    targetScopedServiceClientProvider = { target ->
      bootstrapContext.runtimeServiceAccessGateway.ensureClient(
        bootstrapContext.localizedContext.applicationContext,
        target = target,
      )
    },
    approvalDecisionAccess = approvalDecisionAccess,
    refreshServiceWorkState = serviceWorkStateTracker::refresh,
  )
}

private fun RuntimeServiceBootstrapAssembly.toApprovalDecisionDependencies(
  runtimeServicePort: RuntimeServicePort = retainedOwnerState.currentRuntimeServicePort(),
):
  RuntimeServiceApprovalDecisionDependencies = RuntimeServiceApprovalDecisionDependencies(
  localizedContext = bootstrapContext.localizedContext,
  chatSessionStore = bootstrapContext.chatSessionStore,
  runtimeHostAccess = runtimeServicePort.approvalDecisionHostAccess,
  runtimeReplayAccess = runtimeServicePort.replayAccess,
)

private fun submitRecoverableSubAgentTasksForSession(
  session: OpenCrayRuntimeSessionAccess,
) {
  session.ensureRecoverableDetachedSubAgentTasks()
}

internal fun bootstrapRuntimeServiceSessions(
  chatSessionStore: ChatSessionLocalStore,
  runtimeSessionDirectoryAccess: RuntimeSessionDirectoryAccess,
  runtimeReplayAccess: OpenCrayRuntimeReplayAccess,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory? = null,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory? = null,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory? = null,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
): RuntimeServiceBootstrapResult {
  val knownSessionIds = recoveryCandidateSessionIds(
    chatSessionStore = chatSessionStore,
    snapshotStoreFactory = snapshotStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    subAgentHandleStoreFactory = subAgentHandleStoreFactory,
    runRecordStoreFactory = runRecordStoreFactory,
    runEventJournalStoreFactory = runEventJournalStoreFactory,
  )
  val resumedSessionIds = mutableListOf<String>()
  val repairedSessionIds = mutableListOf<String>()
  val repairEvidenceBySession = linkedMapOf<String, List<InterruptedRunRepairEvidence>>()

  knownSessionIds.forEach { sessionId ->
    val session = runtimeSessionDirectoryAccess.session(sessionId)
    val durableRepairEvidence = durableInteractiveRepairEvidenceForSession(
      sessionId = sessionId,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runRecordStoreFactory = runRecordStoreFactory,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
    )
    val shouldResume = session.hasPendingWork() ||
      session.hasLiveManagedProcesses() ||
      session.hasLiveSubAgentWork() ||
      durableRepairEvidence.isNotEmpty()
    if (!shouldResume) {
      return@forEach
    }
    if (durableRepairEvidence.isNotEmpty()) {
      repairEvidenceBySession[sessionId] = durableRepairEvidence
    }
    session.resume()
    resumedSessionIds += sessionId
    val runs = session.listRuns()
    if (runs.isNotEmpty()) {
      runtimeReplayAccess.terminalReplayRepairer(sessionId, runs)
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
    repairEvidenceBySession = repairEvidenceBySession,
  )
}

internal fun resumeInterruptedRuntimeServiceRuns(
  chatSessionStore: ChatSessionLocalStore,
  runtimeSessionDirectoryAccess: RuntimeSessionDirectoryAccess,
  runtimeReplayAccess: OpenCrayRuntimeReplayAccess,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory? = null,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory? = null,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory? = null,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
): RuntimeServiceInterruptedRunRepairResult {
  val knownSessionIds = recoveryCandidateSessionIds(
    chatSessionStore = chatSessionStore,
    snapshotStoreFactory = snapshotStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    subAgentHandleStoreFactory = subAgentHandleStoreFactory,
    runRecordStoreFactory = runRecordStoreFactory,
    runEventJournalStoreFactory = runEventJournalStoreFactory,
  )
  val resumedSessionIds = mutableListOf<String>()
  val repairedSessionIds = mutableListOf<String>()
  val repairEvidenceBySession = linkedMapOf<String, List<InterruptedRunRepairEvidence>>()

  knownSessionIds.forEach { sessionId ->
    val session = runtimeSessionDirectoryAccess.session(sessionId)
    val runs = session.listRuns()
    val durableRepairEvidence = durableInteractiveRepairEvidenceForSession(
      sessionId = sessionId,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runRecordStoreFactory = runRecordStoreFactory,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
    )
    val shouldResume = runs.any(AgentRunSnapshot::isActive) ||
      session.hasLiveSubAgentWork() ||
      durableRepairEvidence.isNotEmpty()
    if (!shouldResume) {
      return@forEach
    }
    if (durableRepairEvidence.isNotEmpty()) {
      repairEvidenceBySession[sessionId] = durableRepairEvidence
    }
    session.resume()
    resumedSessionIds += sessionId
    val repairedRuns = session.listRuns()
    if (repairedRuns.isNotEmpty()) {
      runtimeReplayAccess.terminalReplayRepairer(sessionId, repairedRuns)
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
    repairEvidenceBySession = repairEvidenceBySession,
  )
}

private fun durableInteractiveRepairEvidenceForSession(
  sessionId: String,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory?,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory?,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory?,
  runRecordStoreFactory: AgentRunRecordStoreFactory?,
  runEventJournalStoreFactory: RunEventJournalStoreFactory?,
): List<InterruptedRunRepairEvidence> {
  if (
    snapshotStoreFactory == null ||
    promptCheckpointStoreFactory == null ||
    subAgentHandleStoreFactory == null
  ) {
    return emptyList()
  }
  return potentialInterruptedRunRepairEvidenceForSession(
    sessionId = sessionId,
    snapshotStoreFactory = snapshotStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    subAgentHandleStoreFactory = subAgentHandleStoreFactory,
    runRecordStoreFactory = runRecordStoreFactory,
    runEventJournalStoreFactory = runEventJournalStoreFactory,
  )
}

internal fun recoveryCandidateSessionIds(
  chatSessionStore: ChatSessionLocalStore,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory?,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory?,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory?,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
): List<String> = buildSet {
  addAll(knownChatSessionIds(chatSessionStore))
  snapshotStoreFactory?.knownSessionIds()?.let(::addAll)
  promptCheckpointStoreFactory?.knownSessionIds()?.let(::addAll)
  subAgentHandleStoreFactory?.knownSessionIds()?.let(::addAll)
  runRecordStoreFactory?.knownSessionIds()?.let(::addAll)
  runEventJournalStoreFactory?.knownSessionIds()?.let(::addAll)
}.toList()
