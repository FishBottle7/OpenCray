package com.opencray.app

import android.content.Context
import android.os.Handler
import android.os.Looper

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

  fun onInterruptedRunRepairResult(result: RuntimeServiceInterruptedRunRepairResult) = Unit

  fun currentOwnerLease(): RuntimeServiceOwnerLease? = null
}

internal class DefaultRuntimeServiceProjectionCoordinator(
  private val runtimeTarget: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  private val localRuntimeServerStateProvider: () -> LocalRuntimeServerState? = { null },
  private val runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor? = null,
  initialInterruptedRunRepairProjection: RuntimeServiceInterruptedRunRepairProjection? = null,
  private val clock: () -> Long = System::currentTimeMillis,
  private val ownerLeaseDurationMs: Long = DEFAULT_RUNTIME_SERVICE_OWNER_LEASE_DURATION_MS,
  private val ownerLeaseHeartbeatIntervalMs: Long =
    DEFAULT_RUNTIME_SERVICE_OWNER_LEASE_HEARTBEAT_INTERVAL_MS,
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
  private val ownerLeaseStore: RuntimeServiceOwnerLeaseStore =
    runCatching {
      FileBackedRuntimeServiceOwnerLeaseStore.fromContext(appContext)
    }.getOrElse {
      inMemoryRuntimeServiceOwnerLeaseStore()
    },
  private val ownerLeaseHeartbeatScheduler: RuntimeServiceDelayScheduler =
    defaultRuntimeServiceOwnerLeaseHeartbeatScheduler(),
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
  private var lastInterruptedRunRepair: RuntimeServiceInterruptedRunRepairProjection? =
    initialInterruptedRunRepairProjection
  private var currentOwnerLease: RuntimeServiceOwnerLease? = null
  private var ownerLeaseAcquiredAtEpochMs: Long = clock()
  private var ownerLeaseHeartbeatTask: RuntimeServiceDelayedTask? = null
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
    scheduleOwnerLeaseHeartbeat()
    persistProjectionSnapshot()
  }

  override fun dispose() {
    val workStateDisposer: (() -> Unit)?
    val runtimeOwnerDisposer: (() -> Unit)?
    val ownerLeaseToRelease: RuntimeServiceOwnerLease?
    synchronized(lock) {
      if (disposed) {
        return
      }
      disposed = true
      started = false
      val lease = currentOwnerLease ?: createOwnerLeaseLocked(clock())
      ownerLeaseToRelease = lease?.released(clock())
      currentOwnerLease = ownerLeaseToRelease
      ownerLeaseHeartbeatTask?.cancel()
      ownerLeaseHeartbeatTask = null
      currentKeepAliveState = RuntimeServiceKeepAliveState()
      workStateDisposer = serviceWorkStateObservationDisposer
      runtimeOwnerDisposer = runtimeOwnerProjectionObservationDisposer
      serviceWorkStateObservationDisposer = null
      runtimeOwnerProjectionObservationDisposer = null
      serviceLifecycle = null
    }
    ownerLeaseToRelease?.let(ownerLeaseStore::release)
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
    val ownerLeaseToRelease: RuntimeServiceOwnerLease?
    synchronized(lock) {
      if (disposed) {
        return
      }
      val now = clock()
      ownerLeaseToRelease = currentOwnerLease?.released(now)
      this.runtimeOwnerLifecycle = runtimeOwnerLifecycle
      this.ownerObservationAccess = ownerObservationAccess
      ownerLeaseAcquiredAtEpochMs = now
      currentOwnerLease = null
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
    ownerLeaseToRelease?.let(ownerLeaseStore::release)
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
    val resolvedInterruptedRunRepair: RuntimeServiceInterruptedRunRepairProjection?
    val resolvedOwnerLease = writeOwnerLeaseHeartbeat()
    synchronized(lock) {
      if (keepAliveState != null) {
        currentKeepAliveState = keepAliveState
      }
      resolvedServiceLifecycle = serviceLifecycle ?: return
      resolvedKeepAliveState = currentKeepAliveState
      resolvedRuntimeOwnerLifecycle = runtimeOwnerLifecycle
      resolvedOwnerObservationAccess = ownerObservationAccess
      resolvedInterruptedRunRepair = lastInterruptedRunRepair
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
        runtimeServiceOwnerLease = resolvedOwnerLease,
        lastInterruptedRunRepair = resolvedInterruptedRunRepair,
      ),
    )
  }

  override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) {
    runtimeNotificationCoordinator?.onScheduledDispatchOutcome(outcome)
  }

  override fun onInterruptedRunRepairResult(result: RuntimeServiceInterruptedRunRepairResult) {
    synchronized(lock) {
      lastInterruptedRunRepair = result.toInterruptedRunRepairProjection(
        recordedAtEpochMs = clock(),
      )
    }
  }

  override fun currentOwnerLease(): RuntimeServiceOwnerLease? = synchronized(lock) {
    currentOwnerLease
  }

  private fun onServiceWorkStateChanged(workState: RuntimeServiceWorkState) {
    persistProjectionSnapshot(workState = workState)
  }

  private fun writeOwnerLeaseHeartbeat(): RuntimeServiceOwnerLease? {
    val lease = synchronized(lock) {
      if (disposed) {
        return null
      }
      createOwnerLeaseLocked(clock())
    } ?: return null
    val savedLease = ownerLeaseStore.save(lease)
    synchronized(lock) {
      currentOwnerLease = savedLease.takeIf { persisted ->
        persisted.sameRuntimeServiceOwnerAs(lease)
      }
    }
    return savedLease
  }

  private fun createOwnerLeaseLocked(nowEpochMs: Long): RuntimeServiceOwnerLease? {
    val resolvedServiceLifecycle = serviceLifecycle ?: return null
    return runtimeServiceOwnerLease(
      target = runtimeTarget,
      runtimeControllerLifecycle = runtimeControllerLifecycle,
      runtimeOwnerLifecycle = runtimeOwnerLifecycle,
      serviceLifecycle = resolvedServiceLifecycle,
      acquiredAtEpochMs = ownerLeaseAcquiredAtEpochMs,
      heartbeatAtEpochMs = nowEpochMs,
      leaseDurationMs = ownerLeaseDurationMs,
    )
  }

  private fun scheduleOwnerLeaseHeartbeat() {
    val shouldSchedule = synchronized(lock) {
      started && !disposed && ownerLeaseHeartbeatTask == null
    }
    if (!shouldSchedule) {
      return
    }
    val task = ownerLeaseHeartbeatScheduler.schedule(
      ownerLeaseHeartbeatIntervalMs.coerceAtLeast(0L),
    ) {
      onOwnerLeaseHeartbeat()
    }
    val shouldKeepTask = synchronized(lock) {
      if (started && !disposed && ownerLeaseHeartbeatTask == null) {
        ownerLeaseHeartbeatTask = task
        true
      } else {
        false
      }
    }
    if (!shouldKeepTask) {
      task.cancel()
    }
  }

  private fun onOwnerLeaseHeartbeat() {
    synchronized(lock) {
      ownerLeaseHeartbeatTask = null
    }
    persistProjectionSnapshot()
    scheduleOwnerLeaseHeartbeat()
  }
}

private fun defaultRuntimeServiceOwnerLeaseHeartbeatScheduler(): RuntimeServiceDelayScheduler =
  runCatching {
    HandlerRuntimeServiceDelayScheduler(Handler(Looper.getMainLooper()))
  }.getOrElse {
    object : RuntimeServiceDelayScheduler {
      override fun schedule(
        delayMs: Long,
        action: () -> Unit,
      ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
    }
  }
