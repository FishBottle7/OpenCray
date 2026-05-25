package com.opencray.app

import android.content.Context

internal interface RuntimeServiceProjectionCoordinator {
  fun bindServiceLifecycle(serviceLifecycle: RuntimeServiceLifecycleDescriptor)

  fun start()

  fun replaceRuntimeOwner(
    runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor,
    ownerObservationAccess: RuntimeOwnerObservationAccess,
    notificationHostAccess: RuntimeNotificationHostAccess,
  ) = Unit

  fun dispose() = Unit

  fun persistProjectionSnapshot(
    workState: RuntimeServiceWorkState? = null,
    keepAliveState: RuntimeServiceKeepAliveState? = null,
  )

  fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome)
}

internal class DefaultRuntimeServiceProjectionCoordinator(
  private val runtimeTarget: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  private val localRuntimeServerStateProvider: () -> LocalRuntimeServerState? = { null },
  private val runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor? = null,
  runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor,
  ownerObservationAccess: RuntimeOwnerObservationAccess,
  notificationHostAccess: RuntimeNotificationHostAccess,
  private val serviceWorkStateTracker: RuntimeServiceWorkStateTracker,
  appContext: Context,
  localizedContext: Context,
  chatSessionStore: ChatSessionLocalStore,
  scheduledTaskSpecStore: ScheduledTaskSpecStore,
  scheduledTaskRunRecordStore: ScheduledTaskRunRecordStore,
  runtimeServiceAccessGateway: RuntimeServiceAccessGateway,
  private val projectionStore: RuntimeServiceProjectionStore =
    runCatching {
      FileBackedRuntimeServiceProjectionStoreFactory.fromContext(appContext).create(runtimeTarget)
    }.getOrElse {
      inMemoryRuntimeServiceProjectionStore()
    },
  private val runtimeNotificationCoordinator: RuntimeNotificationCoordinator? =
    runCatching {
      RuntimeNotificationCoordinator(
        appContext = appContext,
        localizedContext = localizedContext,
        chatSessionStore = chatSessionStore,
        hostAccess = notificationHostAccess,
        scheduledTaskSpecStore = scheduledTaskSpecStore,
        scheduledTaskRunRecordStore = scheduledTaskRunRecordStore,
        notificationSettingsProvider = RuntimeNotificationSettingsStore.fromContext(appContext)::load,
        runtimeServiceAccessGateway = runtimeServiceAccessGateway,
      )
    }.getOrNull(),
) : RuntimeServiceProjectionCoordinator {
  private val lock = Any()
  private var started: Boolean = false
  private var disposed: Boolean = false
  private var serviceLifecycle: RuntimeServiceLifecycleDescriptor? = null
  private var currentKeepAliveState: RuntimeServiceKeepAliveState = RuntimeServiceKeepAliveState()
  private var runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor = runtimeOwnerLifecycle
  private var ownerObservationAccess: RuntimeOwnerObservationAccess = ownerObservationAccess
  private var serviceWorkStateObservationDisposer: (() -> Unit)? = null
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

  override fun bindServiceLifecycle(serviceLifecycle: RuntimeServiceLifecycleDescriptor) {
    synchronized(lock) {
      this.serviceLifecycle = serviceLifecycle
      currentKeepAliveState = RuntimeServiceKeepAliveState()
    }
    start()
    persistProjectionSnapshot(keepAliveState = RuntimeServiceKeepAliveState())
  }

  override fun start() {
    val resolvedOwnerObservationAccess: RuntimeOwnerObservationAccess
    synchronized(lock) {
      if (started || disposed) {
        return
      }
      started = true
      resolvedOwnerObservationAccess = ownerObservationAccess
      serviceWorkStateObservationDisposer = serviceWorkStateTracker.observe(::onServiceWorkStateChanged)
      runtimeOwnerProjectionObservationDisposer = resolvedOwnerObservationAccess.observe(
        runtimeOwnerProjectionListener,
      )
    }
    runtimeNotificationCoordinator?.start()
    persistProjectionSnapshot()
  }

  override fun dispose() {
    val workStateDisposer: (() -> Unit)?
    val runtimeOwnerDisposer: (() -> Unit)?
    synchronized(lock) {
      if (disposed) {
        return
      }
      disposed = true
      started = false
      serviceLifecycle = null
      currentKeepAliveState = RuntimeServiceKeepAliveState()
      workStateDisposer = serviceWorkStateObservationDisposer
      runtimeOwnerDisposer = runtimeOwnerProjectionObservationDisposer
      serviceWorkStateObservationDisposer = null
      runtimeOwnerProjectionObservationDisposer = null
    }
    runtimeNotificationCoordinator?.dispose()
    runtimeOwnerDisposer?.invoke()
    workStateDisposer?.invoke()
  }

  override fun replaceRuntimeOwner(
    runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor,
    ownerObservationAccess: RuntimeOwnerObservationAccess,
    notificationHostAccess: RuntimeNotificationHostAccess,
  ) {
    val previousDisposer: (() -> Unit)?
    synchronized(lock) {
      if (disposed) {
        return
      }
      this.runtimeOwnerLifecycle = runtimeOwnerLifecycle
      this.ownerObservationAccess = ownerObservationAccess
      previousDisposer = if (started) {
        runtimeOwnerProjectionObservationDisposer.also {
          runtimeOwnerProjectionObservationDisposer = ownerObservationAccess.observe(
            runtimeOwnerProjectionListener,
          )
        }
      } else {
        null
      }
    }
    runtimeNotificationCoordinator?.replaceHostAccess(notificationHostAccess)
    previousDisposer?.invoke()
    persistProjectionSnapshot()
  }

  override fun persistProjectionSnapshot(
    workState: RuntimeServiceWorkState?,
    keepAliveState: RuntimeServiceKeepAliveState?,
  ) {
    val resolvedServiceLifecycle: RuntimeServiceLifecycleDescriptor
    val resolvedKeepAliveState: RuntimeServiceKeepAliveState
    val resolvedRuntimeOwnerLifecycle: HostRuntimeLifecycleDescriptor
    val resolvedOwnerObservationAccess: RuntimeOwnerObservationAccess
    synchronized(lock) {
      if (keepAliveState != null) {
        currentKeepAliveState = keepAliveState
      }
      resolvedServiceLifecycle = serviceLifecycle ?: return
      resolvedKeepAliveState = currentKeepAliveState
      resolvedRuntimeOwnerLifecycle = runtimeOwnerLifecycle
      resolvedOwnerObservationAccess = ownerObservationAccess
    }
    projectionStore.saveSnapshot(
      RuntimeServiceProjectionSnapshot(
        localRuntimeServerState = localRuntimeServerStateProvider(),
        runtimeControllerLifecycle = runtimeControllerLifecycle,
        runtimeOwnerLifecycle = resolvedRuntimeOwnerLifecycle,
        runtimeOwnerWorkSummary = resolvedOwnerObservationAccess.activeWorkSummary(),
        serviceLifecycle = resolvedServiceLifecycle,
        serviceWorkState = workState ?: serviceWorkStateTracker.currentState(),
        serviceKeepAliveState = resolvedKeepAliveState,
      ),
    )
  }

  override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) {
    runtimeNotificationCoordinator?.onScheduledDispatchOutcome(outcome)
  }

  private fun onServiceWorkStateChanged(workState: RuntimeServiceWorkState) {
    persistProjectionSnapshot(workState = workState)
  }
}
