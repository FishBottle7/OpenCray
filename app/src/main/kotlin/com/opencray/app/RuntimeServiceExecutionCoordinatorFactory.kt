package com.opencray.app

internal interface RuntimeServiceExecutionCoordinator {
  fun attach()

  fun onStartCommand(startId: Int)

  fun currentKeepAliveState(): RuntimeServiceKeepAliveState

  fun currentForegroundState(): RuntimeForegroundState

  fun persistProjectionSnapshot(
    workState: RuntimeServiceWorkState? = null,
    keepAliveState: RuntimeServiceKeepAliveState? = null,
  )

  fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome)

  fun dispose()
}

internal fun interface RuntimeServiceExecutionCoordinatorFactory {
  fun create(
    appContext: android.content.Context,
    coordinatorDependencies: RuntimeServiceExecutionCoordinatorDependencies,
    keepAliveController: RuntimeServiceKeepAliveController,
    runtimeForegroundController: RuntimeForegroundController,
  ): RuntimeServiceExecutionCoordinator
}

internal object DefaultRuntimeServiceExecutionCoordinatorFactory :
  RuntimeServiceExecutionCoordinatorFactory {
  override fun create(
    appContext: android.content.Context,
    coordinatorDependencies: RuntimeServiceExecutionCoordinatorDependencies,
    keepAliveController: RuntimeServiceKeepAliveController,
    runtimeForegroundController: RuntimeForegroundController,
  ): RuntimeServiceExecutionCoordinator = DefaultRuntimeServiceExecutionCoordinator(
    coordinatorDependencies = coordinatorDependencies,
    keepAliveController = keepAliveController,
    runtimeForegroundController = runtimeForegroundController,
    runtimeNotificationController = RuntimeNotificationCoordinator(
      appContext = appContext,
      localizedContext = coordinatorDependencies.localizedContext,
      chatSessionStore = coordinatorDependencies.chatSessionStore,
      hostAccess = coordinatorDependencies.runtimeHostAccess,
      scheduledTaskSpecStore = coordinatorDependencies.scheduledTaskSpecStore,
      scheduledTaskRunRecordStore = coordinatorDependencies.scheduledTaskRunRecordStore,
      notificationSettingsProvider = RuntimeNotificationSettingsStore.fromContext(appContext)::load,
    ),
    projectionStore = FileBackedRuntimeServiceProjectionStoreFactory.fromContext(appContext).create(),
  )
}

internal data class RuntimeServiceExecutionCoordinatorDependencies(
  val runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor,
  val runtimeHostAccess: OpenCrayRuntimeHostAccess,
  val serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  val serviceWorkStateTracker: RuntimeServiceWorkStateTracker,
  val localizedContext: android.content.Context,
  val chatSessionStore: ChatSessionLocalStore,
  val scheduledTaskSpecStore: ScheduledTaskSpecStore,
  val scheduledTaskRunRecordStore: ScheduledTaskRunRecordStore,
)

private class DefaultRuntimeServiceExecutionCoordinator(
  private val coordinatorDependencies: RuntimeServiceExecutionCoordinatorDependencies,
  private val keepAliveController: RuntimeServiceKeepAliveController,
  private val runtimeForegroundController: RuntimeForegroundController,
  private val runtimeNotificationController: RuntimeNotificationCoordinator,
  private val projectionStore: RuntimeServiceProjectionStore,
) : RuntimeServiceExecutionCoordinator {
  private val lock = Any()
  private var attached: Boolean = false
  private var serviceWorkStateObservationDisposer: (() -> Unit)? = null
  private var keepAliveStateObservationDisposer: (() -> Unit)? = null
  private var runtimeOwnerProjectionObservationDisposer: (() -> Unit)? = null
  private val runtimeOwnerProjectionListener = object : AgentSessionRuntimeListener {
    override fun onTaskStarted(
      sessionId: String,
      task: com.opencray.core.contracts.AgentTask,
    ) {
      persistProjectionSnapshot()
    }

    override fun onTaskFinished(
      sessionId: String,
      task: com.opencray.core.contracts.AgentTask,
      result: com.opencray.core.contracts.ExecutionResult,
    ) {
      persistProjectionSnapshot()
    }
  }

  override fun attach() {
    val serviceWorkStateTracker = coordinatorDependencies.serviceWorkStateTracker
    synchronized(lock) {
      if (attached) {
        return
      }
      attached = true
      serviceWorkStateObservationDisposer = serviceWorkStateTracker.observe(
        ::onServiceWorkStateChanged,
      )
      keepAliveStateObservationDisposer = keepAliveController.observe(
        ::onKeepAliveStateChanged,
      )
      runtimeOwnerProjectionObservationDisposer = coordinatorDependencies.runtimeHostAccess.observe(
        runtimeOwnerProjectionListener,
      )
    }
    runtimeNotificationController.start()
    onServiceWorkStateChanged(serviceWorkStateTracker.currentState())
    persistProjectionSnapshot()
  }

  override fun onStartCommand(startId: Int) {
    keepAliveController.onStartCommand(startId)
  }

  override fun currentKeepAliveState(): RuntimeServiceKeepAliveState = keepAliveController.currentState()

  override fun currentForegroundState(): RuntimeForegroundState = runtimeForegroundController.currentState()

  override fun persistProjectionSnapshot(
    workState: RuntimeServiceWorkState?,
    keepAliveState: RuntimeServiceKeepAliveState?,
  ) {
    projectionStore.saveSnapshot(
      RuntimeServiceProjectionSnapshot(
        runtimeOwnerLifecycle = coordinatorDependencies.runtimeOwnerLifecycle,
        runtimeOwnerWorkSummary = coordinatorDependencies.runtimeHostAccess.activeWorkSummary(),
        serviceLifecycle = coordinatorDependencies.serviceLifecycle,
        serviceWorkState = workState ?: coordinatorDependencies.serviceWorkStateTracker.currentState(),
        serviceKeepAliveState = keepAliveState ?: keepAliveController.currentState(),
      ),
    )
  }

  override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) {
    runtimeNotificationController.onScheduledDispatchOutcome(outcome)
  }

  override fun dispose() {
    val serviceWorkStateDisposer: (() -> Unit)?
    val keepAliveDisposer: (() -> Unit)?
    val runtimeOwnerDisposer: (() -> Unit)?
    synchronized(lock) {
      if (!attached) {
        return
      }
      attached = false
      serviceWorkStateDisposer = serviceWorkStateObservationDisposer
      keepAliveDisposer = keepAliveStateObservationDisposer
      runtimeOwnerDisposer = runtimeOwnerProjectionObservationDisposer
      serviceWorkStateObservationDisposer = null
      keepAliveStateObservationDisposer = null
      runtimeOwnerProjectionObservationDisposer = null
    }
    serviceWorkStateDisposer?.invoke()
    keepAliveDisposer?.invoke()
    runtimeOwnerDisposer?.invoke()
    runtimeNotificationController.dispose()
    runtimeForegroundController.onDestroy()
    val destroyedKeepAliveState = keepAliveController.onDestroy()
    persistProjectionSnapshot(keepAliveState = destroyedKeepAliveState)
  }

  private fun onServiceWorkStateChanged(workState: RuntimeServiceWorkState) {
    keepAliveController.onWorkStateChanged(workState)
    runtimeForegroundController.onWorkStateChanged(workState)
    persistProjectionSnapshot(workState = workState)
  }

  private fun onKeepAliveStateChanged(keepAliveState: RuntimeServiceKeepAliveState) {
    persistProjectionSnapshot(keepAliveState = keepAliveState)
  }
}
