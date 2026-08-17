package com.opencray.app

import android.content.Context
import com.opencray.runtime.process.ManagedProcessRestoreMode
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
  val scheduledWorkScheduler: ScheduledWorkScheduler = NoOpScheduledWorkScheduler,
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
  val nextRepairAfterEpochMs: Long? = null,
  val nextRepairReason: String? = null,
)

internal data class RuntimeServiceInterruptedRunRepairResult(
  val scannedSessionIds: List<String>,
  val resumedSessionIds: List<String>,
  val repairedSessionIds: List<String>,
  val repairEvidenceBySession: Map<String, List<InterruptedRunRepairEvidence>> = emptyMap(),
  val nextRepairAfterEpochMs: Long? = null,
  val nextRepairReason: String? = null,
  val requestedRepairReason: String? = null,
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
    processRegistryFactory = FileBackedAgentProcessRegistryFactory.fromContext(
      context = appContext,
      restoreMode = ManagedProcessRestoreMode.PROJECTION_ONLY,
    ),
    projectedRepairEvidenceBySession = projectedInterruptedRunRepairEvidenceBySession(
      appContext = appContext,
      runtimeTarget = runtimeTarget,
    ),
    runtimeTarget = runtimeTarget,
  )
  scheduleNextInterruptedRunRepairRetry(
    workScheduler = bootstrap.scheduledWorkScheduler,
    nextRepairAfterEpochMs = bootstrapResult.nextRepairAfterEpochMs,
    repairReason = bootstrapResult.nextRepairReason ?: ScheduledTaskRepairReasons.INTERRUPTED_RUN_RETRY,
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
    initialInterruptedRunRepairProjection =
      bootstrapResult.toInterruptedRunRepairProjection(),
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
    scheduledWorkScheduler = bootstrap.scheduledWorkScheduler,
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
    runtimeServiceOwnerLeaseProvider = projectionCoordinator::currentOwnerLease,
    safetySettingsFacade = bootstrapContext.safetySettingsFacade,
    workspaceRootProvider = bootstrapContext.workspaceRootProvider,
    approvedReadRootsProvider = bootstrapContext.approvedReadRootsProvider,
    approvalDecisionAccess = approvalDecisionAccess,
    onDeviceWarmupPlanner = runtimeServicePort.onDeviceWarmupPlanner,
    runtimeServiceOwnerWriteGuard = projectionCoordinator::tryAcquireOwnerLease,
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
    resumeInterruptedRuns = { repairReason ->
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
        processRegistryFactory = FileBackedAgentProcessRegistryFactory.fromContext(
          context = bootstrapContext.localizedContext.applicationContext,
          restoreMode = ManagedProcessRestoreMode.PROJECTION_ONLY,
        ),
        projectedRepairEvidenceBySession = projectedInterruptedRunRepairEvidenceBySession(
          appContext = bootstrapContext.localizedContext.applicationContext,
          runtimeTarget = runtimeTarget,
        ),
        runtimeTarget = runtimeTarget,
      ).copy(
        requestedRepairReason = repairReason,
      ).also { result ->
        scheduleNextInterruptedRunRepairRetry(
          workScheduler = scheduledWorkScheduler,
          nextRepairAfterEpochMs = result.nextRepairAfterEpochMs,
          repairReason = result.nextRepairReason ?: ScheduledTaskRepairReasons.INTERRUPTED_RUN_RETRY,
        )
      }
    },
    approvalDecisionAccess = approvalDecisionAccess,
    refreshServiceWorkState = serviceWorkStateTracker::refresh,
    runtimeTarget = runtimeTarget,
    scheduledTaskForwarder = { command, target ->
      bootstrapContext.runtimeServiceAccessGateway.startScheduledTask(
        context = bootstrapContext.localizedContext.applicationContext,
        command = command,
        target = target,
      )
    },
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

internal fun bootstrapRuntimeServiceSessions(
  chatSessionStore: ChatSessionLocalStore,
  runtimeSessionDirectoryAccess: RuntimeSessionDirectoryAccess,
  runtimeReplayAccess: OpenCrayRuntimeReplayAccess,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory? = null,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory? = null,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory? = null,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
  processRegistryFactory: AgentProcessRegistryFactory? = null,
  projectedRepairEvidenceBySession: Map<String, List<InterruptedRunRepairEvidence>> = emptyMap(),
  nowEpochMs: Long = System.currentTimeMillis(),
  runtimeTarget: RuntimeServiceTarget? = null,
): RuntimeServiceBootstrapResult {
  val knownSessionIds = recoveryCandidateSessionIds(
    chatSessionStore = chatSessionStore,
    snapshotStoreFactory = snapshotStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    subAgentHandleStoreFactory = subAgentHandleStoreFactory,
    runRecordStoreFactory = runRecordStoreFactory,
    runEventJournalStoreFactory = runEventJournalStoreFactory,
    processRegistryFactory = processRegistryFactory,
    projectedRepairEvidenceBySession = projectedRepairEvidenceBySession,
  )
  val resumedSessionIds = mutableListOf<String>()
  val repairedSessionIds = mutableListOf<String>()
  val repairEvidenceBySession = linkedMapOf<String, List<InterruptedRunRepairEvidence>>()

  knownSessionIds.forEach { sessionId ->
    val durableRepairEvidence = durableInteractiveRepairEvidenceForSession(
      sessionId = sessionId,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runRecordStoreFactory = runRecordStoreFactory,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
      processRegistryFactory = processRegistryFactory,
    )
    val allRepairEvidence = mergedInterruptedRunRepairEvidence(
      durableRepairEvidence = durableRepairEvidence,
      projectedRepairEvidence = projectedRepairEvidenceForSession(
        sessionId = sessionId,
        projectedRepairEvidenceBySession = projectedRepairEvidenceBySession,
        snapshotStoreFactory = snapshotStoreFactory,
        runRecordStoreFactory = runRecordStoreFactory,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
      ),
    )
    if (!shouldOwnSessionForRecovery(
        sessionId = sessionId,
        runtimeTarget = runtimeTarget,
        runtimeSessionDirectoryAccess = runtimeSessionDirectoryAccess,
        repairEvidence = allRepairEvidence,
      )
    ) {
      if (allRepairEvidence.isNotEmpty()) {
        repairEvidenceBySession[sessionId] = allRepairEvidence
      }
      return@forEach
    }
    val repairEvidence = runtimeTarget
      ?.let { target -> allRepairEvidence.filter { evidence -> evidence.target == target } }
      ?: allRepairEvidence
    val dueRepairEvidence = dueInterruptedRunRepairEvidence(
      evidence = repairEvidence,
      nowEpochMs = nowEpochMs,
    )
    if (repairEvidence.isNotEmpty()) {
      repairEvidenceBySession[sessionId] = repairEvidence
    }
    val session = try {
      runtimeSessionDirectoryAccess.session(sessionId)
    } catch (_: RuntimeSessionOwnershipException) {
      return@forEach
    }
    val shouldResume = session.hasPendingWork() ||
      session.hasLiveManagedProcesses() ||
      session.hasLiveSubAgentWork() ||
      dueRepairEvidence.isNotEmpty()
    if (!shouldResume) {
      return@forEach
    }
    session.resume()
    resumedSessionIds += sessionId
    val runs = session.listRuns()
    if (runs.isNotEmpty()) {
      runtimeReplayAccess.terminalReplayRepairer(sessionId, runs)
      repairedSessionIds += sessionId
    }
  }

  val nextRepairRetry = nextInterruptedRunRepairRetry(
    evidence = repairEvidenceBySession.values.flatten(),
    nowEpochMs = nowEpochMs,
  )
  return RuntimeServiceBootstrapResult(
    scannedSessionIds = knownSessionIds,
    resumedSessionIds = resumedSessionIds,
    repairedSessionIds = repairedSessionIds,
    repairEvidenceBySession = repairEvidenceBySession,
    nextRepairAfterEpochMs = nextRepairRetry?.repairAfterEpochMs,
    nextRepairReason = nextRepairRetry?.repairReason,
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
  processRegistryFactory: AgentProcessRegistryFactory? = null,
  projectedRepairEvidenceBySession: Map<String, List<InterruptedRunRepairEvidence>> = emptyMap(),
  nowEpochMs: Long = System.currentTimeMillis(),
  runtimeTarget: RuntimeServiceTarget? = null,
): RuntimeServiceInterruptedRunRepairResult {
  val knownSessionIds = recoveryCandidateSessionIds(
    chatSessionStore = chatSessionStore,
    snapshotStoreFactory = snapshotStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    subAgentHandleStoreFactory = subAgentHandleStoreFactory,
    runRecordStoreFactory = runRecordStoreFactory,
    runEventJournalStoreFactory = runEventJournalStoreFactory,
    processRegistryFactory = processRegistryFactory,
    projectedRepairEvidenceBySession = projectedRepairEvidenceBySession,
  )
  val resumedSessionIds = mutableListOf<String>()
  val repairedSessionIds = mutableListOf<String>()
  val repairEvidenceBySession = linkedMapOf<String, List<InterruptedRunRepairEvidence>>()

  knownSessionIds.forEach { sessionId ->
    val durableRepairEvidence = durableInteractiveRepairEvidenceForSession(
      sessionId = sessionId,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runRecordStoreFactory = runRecordStoreFactory,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
      processRegistryFactory = processRegistryFactory,
    )
    val allRepairEvidence = mergedInterruptedRunRepairEvidence(
      durableRepairEvidence = durableRepairEvidence,
      projectedRepairEvidence = projectedRepairEvidenceForSession(
        sessionId = sessionId,
        projectedRepairEvidenceBySession = projectedRepairEvidenceBySession,
        snapshotStoreFactory = snapshotStoreFactory,
        runRecordStoreFactory = runRecordStoreFactory,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
      ),
    )
    if (!shouldOwnSessionForRecovery(
        sessionId = sessionId,
        runtimeTarget = runtimeTarget,
        runtimeSessionDirectoryAccess = runtimeSessionDirectoryAccess,
        repairEvidence = allRepairEvidence,
      )
    ) {
      if (allRepairEvidence.isNotEmpty()) {
        repairEvidenceBySession[sessionId] = allRepairEvidence
      }
      return@forEach
    }
    val repairEvidence = runtimeTarget
      ?.let { target -> allRepairEvidence.filter { evidence -> evidence.target == target } }
      ?: allRepairEvidence
    val dueRepairEvidence = dueInterruptedRunRepairEvidence(
      evidence = repairEvidence,
      nowEpochMs = nowEpochMs,
    )
    if (repairEvidence.isNotEmpty()) {
      repairEvidenceBySession[sessionId] = repairEvidence
    }
    val session = try {
      runtimeSessionDirectoryAccess.session(sessionId)
    } catch (_: RuntimeSessionOwnershipException) {
      return@forEach
    }
    val runs = session.listRuns()
    val shouldResume = runs.any(AgentRunSnapshot::isActive) ||
      session.hasLiveSubAgentWork() ||
      dueRepairEvidence.isNotEmpty()
    if (!shouldResume) {
      return@forEach
    }
    session.resume()
    resumedSessionIds += sessionId
    val repairedRuns = session.listRuns()
    if (repairedRuns.isNotEmpty()) {
      runtimeReplayAccess.terminalReplayRepairer(sessionId, repairedRuns)
      repairedSessionIds += sessionId
    }
  }

  val nextRepairRetry = nextInterruptedRunRepairRetry(
    evidence = repairEvidenceBySession.values.flatten(),
    nowEpochMs = nowEpochMs,
  )
  return RuntimeServiceInterruptedRunRepairResult(
    scannedSessionIds = knownSessionIds,
    resumedSessionIds = resumedSessionIds,
    repairedSessionIds = repairedSessionIds,
    repairEvidenceBySession = repairEvidenceBySession,
    nextRepairAfterEpochMs = nextRepairRetry?.repairAfterEpochMs,
    nextRepairReason = nextRepairRetry?.repairReason,
  )
}

private fun shouldOwnSessionForRecovery(
  sessionId: String,
  runtimeTarget: RuntimeServiceTarget?,
  runtimeSessionDirectoryAccess: RuntimeSessionDirectoryAccess,
  repairEvidence: List<InterruptedRunRepairEvidence>,
): Boolean {
  val target = runtimeTarget ?: return true
  val currentOwnerTarget = runtimeSessionDirectoryAccess.sessionOwnerTarget(sessionId)
  if (currentOwnerTarget != null) {
    return currentOwnerTarget == target
  }
  return repairEvidence.firstOrNull()?.target?.let { evidenceTarget ->
    evidenceTarget == target
  } ?: (runtimeSessionDirectoryAccess.existingSession(sessionId) != null)
}

private fun durableInteractiveRepairEvidenceForSession(
  sessionId: String,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory?,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory?,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory?,
  runRecordStoreFactory: AgentRunRecordStoreFactory?,
  runEventJournalStoreFactory: RunEventJournalStoreFactory?,
  processRegistryFactory: AgentProcessRegistryFactory?,
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
    processRegistryFactory = processRegistryFactory,
  )
}

private fun projectedInterruptedRunRepairEvidenceBySession(
  appContext: Context,
  runtimeTarget: RuntimeServiceTarget,
): Map<String, List<InterruptedRunRepairEvidence>> =
  runCatching {
    FileBackedRuntimeServiceProjectionStoreFactory
      .fromContext(appContext)
      .create(runtimeTarget)
      .loadSnapshot()
      ?.lastInterruptedRunRepair
      ?.repairEvidenceBySession
      .orEmpty()
  }.getOrDefault(emptyMap())

private fun mergedInterruptedRunRepairEvidence(
  durableRepairEvidence: List<InterruptedRunRepairEvidence>,
  projectedRepairEvidence: List<InterruptedRunRepairEvidence>,
): List<InterruptedRunRepairEvidence> =
  (durableRepairEvidence + projectedRepairEvidence)
    .distinct()
    .withManagedProcessReconnectBackoff()

private fun projectedRepairEvidenceForSession(
  sessionId: String,
  projectedRepairEvidenceBySession: Map<String, List<InterruptedRunRepairEvidence>>,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory?,
  runRecordStoreFactory: AgentRunRecordStoreFactory?,
  runEventJournalStoreFactory: RunEventJournalStoreFactory?,
): List<InterruptedRunRepairEvidence> {
  val projectedRepairEvidence = projectedRepairEvidenceBySession[sessionId].orEmpty()
  if (projectedRepairEvidence.isEmpty()) {
    return projectedRepairEvidence
  }
  val terminalIdentities = terminalProjectionRepairIdentities(
    sessionId = sessionId,
    taskSnapshots = runCatching {
      snapshotStoreFactory
        ?.forChatSession(sessionId)
        ?.load()
        ?.tasks
        .orEmpty()
    }.getOrDefault(emptyList()),
    runRecords = runCatching {
      runRecordStoreFactory
        ?.forChatSession(sessionId)
        ?.list()
        .orEmpty()
    }.getOrDefault(emptyList()),
    journalEntries = runCatching {
      runEventJournalStoreFactory
        ?.forChatSession(sessionId)
        ?.list()
        .orEmpty()
    }.getOrDefault(emptyList()),
  )
  if (terminalIdentities.isEmpty()) {
    return projectedRepairEvidence
  }
  return projectedRepairEvidence.filterNot { evidence ->
    evidence.matchesRepairIdentity(terminalIdentities)
  }
}

internal fun recoveryCandidateSessionIds(
  chatSessionStore: ChatSessionLocalStore,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory?,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory?,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory?,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
  processRegistryFactory: AgentProcessRegistryFactory? = null,
  projectedRepairEvidenceBySession: Map<String, List<InterruptedRunRepairEvidence>> = emptyMap(),
): List<String> = buildSet {
  addAll(knownChatSessionIds(chatSessionStore))
  snapshotStoreFactory?.knownSessionIds()?.let(::addAll)
  promptCheckpointStoreFactory?.knownSessionIds()?.let(::addAll)
  subAgentHandleStoreFactory?.knownSessionIds()?.let(::addAll)
  runRecordStoreFactory?.knownSessionIds()?.let(::addAll)
  runEventJournalStoreFactory?.knownSessionIds()?.let(::addAll)
  processRegistryFactory?.knownSessionIds()?.let(::addAll)
  addAll(projectedRepairEvidenceBySession.keys)
}.toList()
