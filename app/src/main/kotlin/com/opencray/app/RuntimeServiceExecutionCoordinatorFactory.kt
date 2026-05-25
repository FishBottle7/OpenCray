package com.opencray.app

internal interface RuntimeServiceShellStateAccess {
  fun currentKeepAliveState(): RuntimeServiceKeepAliveState

  fun currentForegroundState(): RuntimeForegroundState
}

internal interface RuntimeServiceExecutionCoordinator : RuntimeServiceShellStateAccess {
  fun attach()

  fun onStartCommand(startId: Int)

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
  )
}

internal data class RuntimeServiceExecutionCoordinatorDependencies(
  val projectionCoordinator: RuntimeServiceProjectionCoordinator,
  val serviceWorkStateTracker: RuntimeServiceWorkStateTracker,
)

private class DefaultRuntimeServiceExecutionCoordinator(
  private val coordinatorDependencies: RuntimeServiceExecutionCoordinatorDependencies,
  private val keepAliveController: RuntimeServiceKeepAliveController,
  private val runtimeForegroundController: RuntimeForegroundController,
) : RuntimeServiceExecutionCoordinator {
  private val lock = Any()
  private var attached: Boolean = false
  private var serviceWorkStateObservationDisposer: (() -> Unit)? = null
  private var keepAliveStateObservationDisposer: (() -> Unit)? = null

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
    }
    val currentWorkState = serviceWorkStateTracker.currentState()
    keepAliveController.onWorkStateChanged(currentWorkState)
    runtimeForegroundController.onWorkStateChanged(currentWorkState)
    val currentKeepAliveState = keepAliveController.currentState()
    onKeepAliveStateChanged(currentKeepAliveState)
  }

  override fun onStartCommand(startId: Int) {
    val keepAliveState = keepAliveController.onStartCommand(startId)
    persistProjectionSnapshot(keepAliveState = keepAliveState)
  }

  override fun currentKeepAliveState(): RuntimeServiceKeepAliveState = keepAliveController.currentState()

  override fun currentForegroundState(): RuntimeForegroundState = runtimeForegroundController.currentState()

  override fun persistProjectionSnapshot(
    workState: RuntimeServiceWorkState?,
    keepAliveState: RuntimeServiceKeepAliveState?,
  ) = coordinatorDependencies.projectionCoordinator.persistProjectionSnapshot(
    workState = workState,
    keepAliveState = keepAliveState ?: keepAliveController.currentState(),
  )

  override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) {
    coordinatorDependencies.projectionCoordinator.onScheduledDispatchOutcome(outcome)
  }

  override fun dispose() {
    val serviceWorkStateDisposer: (() -> Unit)?
    val keepAliveDisposer: (() -> Unit)?
    synchronized(lock) {
      if (!attached) {
        return
      }
      attached = false
      serviceWorkStateDisposer = serviceWorkStateObservationDisposer
      keepAliveDisposer = keepAliveStateObservationDisposer
      serviceWorkStateObservationDisposer = null
      keepAliveStateObservationDisposer = null
    }
    serviceWorkStateDisposer?.invoke()
    keepAliveDisposer?.invoke()
    persistProjectionSnapshot(keepAliveState = keepAliveController.currentState())
  }

  private fun onServiceWorkStateChanged(workState: RuntimeServiceWorkState) {
    keepAliveController.onWorkStateChanged(workState)
    runtimeForegroundController.onWorkStateChanged(workState)
  }

  private fun onKeepAliveStateChanged(keepAliveState: RuntimeServiceKeepAliveState) {
    runtimeForegroundController.onKeepAliveStateChanged(keepAliveState)
    persistProjectionSnapshot(keepAliveState = keepAliveState)
  }
}
