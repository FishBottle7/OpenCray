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
      runtimeSessionDirectoryAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
    )

  fun approvePendingApproval(
    taskIdOrRunId: String,
    nowEpochMs: Long = System.currentTimeMillis(),
  ) {
    runtimeServiceApprovalDecisionAccess(
      dependencies = RuntimeServiceApprovalDecisionDependencies(
        localizedContext = dependencies.localizedContext,
        chatSessionStore = dependencies.chatSessionStore,
        runtimeHostAccess = runtimeAccess.hostAccess,
        runtimeReplayAccess = runtimeAccess.replayAccess,
      ),
      nowEpochMsProvider = { nowEpochMs },
    ).approve(taskIdOrRunId)
  }

  fun approvePendingApprovalForSession(
    taskIdOrRunId: String,
    nowEpochMs: Long = System.currentTimeMillis(),
  ) {
    runtimeServiceApprovalDecisionAccess(
      dependencies = RuntimeServiceApprovalDecisionDependencies(
        localizedContext = dependencies.localizedContext,
        chatSessionStore = dependencies.chatSessionStore,
        runtimeHostAccess = runtimeAccess.hostAccess,
        runtimeReplayAccess = runtimeAccess.replayAccess,
      ),
      nowEpochMsProvider = { nowEpochMs },
    ).approveForSession(taskIdOrRunId)
  }

  fun rejectPendingApproval(
    taskIdOrRunId: String,
    nowEpochMs: Long = System.currentTimeMillis(),
  ) {
    runtimeServiceApprovalDecisionAccess(
      dependencies = RuntimeServiceApprovalDecisionDependencies(
        localizedContext = dependencies.localizedContext,
        chatSessionStore = dependencies.chatSessionStore,
        runtimeHostAccess = runtimeAccess.hostAccess,
        runtimeReplayAccess = runtimeAccess.replayAccess,
      ),
      nowEpochMsProvider = { nowEpochMs },
    ).reject(taskIdOrRunId)
  }
}

internal fun OpenCrayRuntimeServiceHost.toRuntimeServiceBootstrapAssembly():
  RuntimeServiceBootstrapAssembly = RuntimeServiceBootstrapAssembly(
  bootstrapContext = runtimeServiceBootstrapContext(dependencies),
  retainedOwnerState = RuntimeServiceRetainedOwnerState(
    initialBootstrap = RuntimeOwnerBootstrap(
      runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
      ownerObservationAccess = runtimeAccess.hostAccess,
      notificationHostAccess = runtimeAccess.hostAccess,
      approvalDecisionHostAccess = runtimeAccess.hostAccess,
      chatMutationAccess = runtimeAccess.hostAccess,
      chatSubmissionHostAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
    ),
    replacementBootstrapProvider = { currentBootstrap ->
      RuntimeOwnerBootstrap(
        runtimeOwnerLifecycle = currentBootstrap.runtimeOwnerLifecycle,
        ownerObservationAccess = runtimeAccess.hostAccess,
        notificationHostAccess = runtimeAccess.hostAccess,
        approvalDecisionHostAccess = runtimeAccess.hostAccess,
        chatMutationAccess = runtimeAccess.hostAccess,
        chatSubmissionHostAccess = runtimeAccess.hostAccess,
        runtimeReplayAccess = runtimeAccess.replayAccess,
      )
    },
  ),
  projectionCoordinator = testRuntimeServiceProjectionCoordinator(),
  transportCoordinator = DefaultRuntimeServiceTransportCoordinator(),
  retainedShellControl = testRuntimeServiceRetainedShellControl(),
  bootstrapResult = bootstrapResult,
  serviceWorkStateTracker = serviceWorkStateTracker,
  scheduledTaskSpecStore = scheduledTaskSpecStore,
  scheduledTaskRunRecordStore = scheduledTaskRunRecordStore,
  scheduledTaskTriggerSyncStateStore = scheduledTaskTriggerSyncStateStore,
  scheduledTriggerRegistrar = scheduledTriggerRegistrar,
)

internal fun OpenCrayRuntimeServiceHost.toRuntimeServiceBootstrapState():
  RuntimeServiceBootstrapState = toRuntimeServiceBootstrapAssembly().toRuntimeServiceBootstrapState(
    serviceLifecycle = serviceLifecycle,
  )

internal fun createRuntimeServiceBootstrapState(
  appContext: android.content.Context,
  serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  runtimeExecutionDependenciesLoader: RuntimeExecutionDependenciesLoader =
    testRuntimeExecutionDependenciesLoader(),
  runtimeOwnerBootstrapProvider: RuntimeOwnerBootstrapProvider =
    defaultRuntimeOwnerBootstrapProvider(),
  retainedShellControlFactory: (android.content.Context) -> RuntimeServiceRetainedShellControl =
    { testRuntimeServiceRetainedShellControl() },
  bootstrapFactory: RuntimeServiceBootstrapFactory =
    DefaultRuntimeServiceBootstrapFactory,
): RuntimeServiceBootstrapState {
  val applicationContext = appContext.applicationContext
  val executionDependencies = runtimeExecutionDependenciesLoader.load(applicationContext)
  val runtimeOwnerDependencies = executionDependencies.runtimeOwnerBootstrapDependencies
  val runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor()
  val runtimeOwnerBootstrap = runtimeOwnerBootstrapProvider.resolve(
    runtimeOwnerDependencies,
    runtimeControllerLifecycle,
  )
  return createRuntimeServiceBootstrapAssembly(
    appContext = executionDependencies.appContext,
    bootstrapContext = executionDependencies.bootstrapContext,
    retainedOwnerState = RuntimeServiceRetainedOwnerState(
      initialBootstrap = runtimeOwnerBootstrap,
      replacementBootstrapProvider = { currentBootstrap ->
        runtimeOwnerBootstrapProvider.replace(
          runtimeOwnerDependencies,
          runtimeControllerLifecycle,
          currentBootstrap,
        )
      },
      finalBootstrapDisposer = runtimeOwnerBootstrapProvider::disposeRetainedBootstrap,
    ),
    runtimeControllerLifecycle = runtimeControllerLifecycle,
    retainedShellControlFactory = retainedShellControlFactory,
    bootstrapFactory = bootstrapFactory,
  ).toRuntimeServiceBootstrapState(serviceLifecycle = serviceLifecycle)
}

internal fun testRuntimeServiceBootstrapDependencies(
  runtimeServiceExecutionControllerProvider: RuntimeServiceExecutionControllerProvider =
    ProcessScopedRuntimeServiceExecutionControllerProvider(
      runtimeExecutionDependenciesLoader = testRuntimeExecutionDependenciesLoader(),
      runtimeOwnerBootstrapProvider = defaultRuntimeOwnerBootstrapProvider(),
    ),
  localHostGatewayProvider: (android.content.Context) -> OpenCrayLocalHostGateway =
    {
      DefaultOpenCrayLocalHostGateway(
        appContext = null,
        workspaceRootProvider = null,
        workspaceSnapshotProvider = { emptyMap() },
      )
    },
  runtimeServiceGatewayBundleFactory: RuntimeServiceGatewayBundleFactory =
    DefaultRuntimeServiceGatewayBundleFactory,
  runtimeServiceTransportBootstrapFactory: OpenCrayRuntimeServiceTransportBootstrapFactory =
    DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(),
  runtimeServiceExecutionCoordinatorFactory: RuntimeServiceExecutionCoordinatorFactory =
    DefaultRuntimeServiceExecutionCoordinatorFactory,
  runtimeServiceShellControlBundleFactory: RuntimeServiceShellControlBundleFactory =
    DefaultRuntimeServiceShellControlBundleFactory(),
  runtimeServiceWakeCommandDispatcherFactory: RuntimeServiceWakeCommandDispatcherFactory =
    DefaultRuntimeServiceWakeCommandDispatcherFactory,
  runtimeServiceBinderEndpointFactory: RuntimeServiceBinderEndpointFactory =
    DefaultRuntimeServiceBinderEndpointFactory,
): RuntimeServiceBootstrapDependencies = RuntimeServiceBootstrapDependencies(
  runtimeServiceBootstrapStateProvider = RuntimeServiceBootstrapStateProvider {
      context,
      _,
      serviceLifecycle,
    ->
    val executionController = runtimeServiceExecutionControllerProvider.resolve(context)
    RuntimeServiceResolvedBootstrap(
      bootstrapState = executionController.toRuntimeServiceBootstrapState(serviceLifecycle),
      resetRuntimeOwnerAction = {
        executionController.replaceRuntimeOwner()
      },
    )
  },
  localHostGatewayProvider = localHostGatewayProvider,
  runtimeServiceGatewayBundleFactory = runtimeServiceGatewayBundleFactory,
  runtimeServiceTransportBootstrapFactory = runtimeServiceTransportBootstrapFactory,
  runtimeServiceExecutionCoordinatorFactory = runtimeServiceExecutionCoordinatorFactory,
  runtimeServiceShellControlBundleFactory = runtimeServiceShellControlBundleFactory,
  runtimeServiceWakeCommandDispatcherFactory = runtimeServiceWakeCommandDispatcherFactory,
  runtimeServiceBinderEndpointFactory = runtimeServiceBinderEndpointFactory,
)

internal data class RuntimeServiceAccessResetResult(
  val previousClient: OpenCrayRuntimeServiceClient?,
  val previousExecutionController: RuntimeServiceExecutionController?,
)

internal fun currentDefaultRuntimeServiceExecutionController(): RuntimeServiceExecutionController? =
  defaultProcessScopedRuntimeServiceExecutionControllerProviderForTest().peek()

internal fun resetDefaultRuntimeServiceExecutionController(): RuntimeServiceExecutionController? =
  defaultProcessScopedRuntimeServiceExecutionControllerProviderForTest().reset()

internal fun replaceDefaultRuntimeServiceExecutionController(
  controller: RuntimeServiceExecutionController?,
): RuntimeServiceExecutionController? =
  defaultProcessScopedRuntimeServiceExecutionControllerProviderForTest().swap(controller)

internal fun resetDetachedRuntimeForTest(): RuntimeServiceAccessResetResult =
  RuntimeServiceAccessResetResult(
    previousClient = OpenCrayRuntimeServiceAccess.dropCachedClientForTest(),
    previousExecutionController = resetDefaultRuntimeServiceExecutionController(),
  )

internal fun replaceDetachedRuntimeExecutionControllerForTest(
  controller: RuntimeServiceExecutionController?,
): RuntimeServiceAccessResetResult = RuntimeServiceAccessResetResult(
  previousClient = OpenCrayRuntimeServiceAccess.dropCachedClientForTest(),
  previousExecutionController = replaceDefaultRuntimeServiceExecutionController(controller),
)

internal fun testRuntimeExecutionDependenciesLoader():
  RuntimeExecutionDependenciesLoader = RuntimeExecutionDependenciesLoader { appContext ->
    loadRuntimeExecutionDependencies(
      appContext = appContext,
      runtimeEnvironment = openCrayRuntimeServiceEnvironment(appContext),
    )
  }

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

internal object InProcessOpenCrayRuntimeOwnerRegistry {
  private var instance: Any? = null

  fun peek(): Any? = instance

  fun clearForTest() {
    instance = null
  }
}

private fun testRuntimeServiceProjectionCoordinator(): RuntimeServiceProjectionCoordinator =
  object : RuntimeServiceProjectionCoordinator {
    override fun bindServiceLifecycle(serviceLifecycle: RuntimeServiceLifecycleDescriptor) = Unit

    override fun start() = Unit

    override fun persistProjectionSnapshot(
      workState: RuntimeServiceWorkState?,
      keepAliveState: RuntimeServiceKeepAliveState?,
    ) = Unit

    override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) = Unit
  }

private fun testRuntimeServiceRetainedShellControl(): RuntimeServiceRetainedShellControl =
  RuntimeServiceRetainedShellControl(
    keepAliveController = RuntimeServiceKeepAliveController(
      appVisibleProvider = { true },
      scheduler = object : RuntimeServiceDelayScheduler {
        override fun schedule(
          delayMs: Long,
          action: () -> Unit,
        ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
      },
      stopRequester = { false },
    ),
    runtimeForegroundController = RuntimeForegroundController(
      serviceAdapter = object : RuntimeForegroundServiceAdapter {
        override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

        override fun stopForeground(removeNotification: Boolean) = Unit
      },
      appVisibleProvider = { true },
    ),
  )
