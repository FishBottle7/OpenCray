package com.opencray.app

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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

  fun tryAcquireOwnerLease(): Boolean = true
}

internal object NoOpRuntimeServiceProjectionCoordinator : RuntimeServiceProjectionCoordinator {
  override fun bindServiceLifecycle(serviceLifecycle: RuntimeServiceLifecycleDescriptor) = Unit

  override fun start() = Unit

  override fun persistProjectionSnapshot(
    workState: RuntimeServiceWorkState?,
    keepAliveState: RuntimeServiceKeepAliveState?,
  ) = Unit

  override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) = Unit
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
    defaultRuntimeServiceProjectionStoreOrInMemoryDegraded(appContext, runtimeTarget),
  private val ownerLeaseStore: RuntimeServiceOwnerLeaseStore =
    FileBackedRuntimeServiceOwnerLeaseStore.fromContext(appContext),
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
  private var boundServiceLifecycle: RuntimeServiceLifecycleDescriptor? = null
  private var activeServiceLifecycle: RuntimeServiceLifecycleDescriptor? = null
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
      boundServiceLifecycle = serviceLifecycle
    }
  }

  override fun start() {
    var shouldStartObservers = false
    synchronized(lock) {
      if (disposed || boundServiceLifecycle == null) {
        return
      }
      activeServiceLifecycle = boundServiceLifecycle
      if (!started) {
        started = true
        shouldStartObservers = true
        serviceWorkStateObservationDisposer = serviceWorkStateTracker.observe(::onServiceWorkStateChanged)
        runtimeOwnerProjectionObservationDisposer = ownerObservationAccess.observe(
          runtimeOwnerProjectionListener,
        )
      }
    }
    if (shouldStartObservers) {
      runtimeNotificationCoordinator?.start()
    }
    scheduleOwnerLeaseHeartbeat()
    persistProjectionSnapshot()
  }

  override fun dispose() {
    val workStateDisposer: (() -> Unit)?
    val runtimeOwnerDisposer: (() -> Unit)?
    val ownerLeaseToRelease: RuntimeServiceOwnerLease?
    val releasedProjectionState: ReleasedOwnerLeaseProjectionState?
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
      releasedProjectionState = if (ownerLeaseToRelease != null) {
        ReleasedOwnerLeaseProjectionState(
          serviceLifecycle = boundServiceLifecycle ?: activeServiceLifecycle,
          runtimeOwnerLifecycle = runtimeOwnerLifecycle,
          ownerObservationAccess = ownerObservationAccess,
          interruptedRunRepair = lastInterruptedRunRepair,
        )
      } else {
        null
      }
      workStateDisposer = serviceWorkStateObservationDisposer
      runtimeOwnerDisposer = runtimeOwnerProjectionObservationDisposer
      serviceWorkStateObservationDisposer = null
      runtimeOwnerProjectionObservationDisposer = null
      boundServiceLifecycle = null
      activeServiceLifecycle = null
    }
    runCleanupPhase("ownerLeaseHeartbeatSchedulerShutdown") {
      (ownerLeaseHeartbeatScheduler as? OwnerLeaseHeartbeatDelayScheduler)?.shutdown()
    }
    val persistedReleasedLease: RuntimeServiceOwnerLease? = ownerLeaseToRelease?.let { lease ->
      try {
        ownerLeaseStore.release(lease)
      } catch (failure: Exception) {
        logCleanupPhaseFailure("ownerLeaseRelease", failure)
        null
      }
    }
    if (
      persistedReleasedLease != null &&
        releasedProjectionState?.serviceLifecycle != null &&
        persistedReleasedLease.sameRuntimeServiceOwnerAs(checkNotNull(ownerLeaseToRelease)) &&
        persistedReleasedLease.phase == RuntimeServiceOwnerLease.PHASE_RELEASED
    ) {
      runCleanupPhase("releasedOwnerLeaseProjectionPersist") {
        persistReleasedOwnerLeaseProjection(
          releasedLease = persistedReleasedLease,
          projectionState = checkNotNull(releasedProjectionState),
        )
      }
    }
    runCleanupPhase("notificationCoordinatorDispose") {
      runtimeNotificationCoordinator?.dispose()
    }
    runCleanupPhase("runtimeOwnerObserverDispose") { runtimeOwnerDisposer?.invoke() }
    runCleanupPhase("serviceWorkStateObserverDispose") { workStateDisposer?.invoke() }
  }

  override fun replaceRuntimeOwner(
    runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor,
    ownerObservationAccess: RuntimeOwnerObservationAccess,
    notificationHostAccess: RuntimeNotificationHostAccess,
  ) {
    val previousDisposer: (() -> Unit)?
    val ownerLeaseToRelease: RuntimeServiceOwnerLease?
    var replacementObserverStarted = false
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
      replacementObserverStarted = started
      previousDisposer = if (started) {
        runtimeOwnerProjectionObservationDisposer.also {
          runtimeOwnerProjectionObservationDisposer = null
        }
      } else {
        null
      }
    }
    if (replacementObserverStarted) {
      installReplacementOwnerObserver(ownerObservationAccess)
    }
    runCleanupPhase("previousOwnerLeaseRelease") {
      ownerLeaseToRelease?.let(ownerLeaseStore::release)
    }
    runCleanupPhase("notificationHostAccessReplace") {
      runtimeNotificationCoordinator?.replaceHostAccess(notificationHostAccess)
    }
    runCleanupPhase("previousOwnerObserverDispose") { previousDisposer?.invoke() }
    runCleanupPhase("replacementOwnerProjectionPersist") { persistProjectionSnapshot() }
  }

  private fun installReplacementOwnerObserver(access: RuntimeOwnerObservationAccess) {
    val disposer = try {
      access.observe(runtimeOwnerProjectionListener)
    } catch (failure: Exception) {
      logCleanupPhaseFailure("replacementObserverInstall", failure)
      return
    }
    var orphanedDisposer: (() -> Unit)? = null
    synchronized(lock) {
      if (disposed || !started) {
        orphanedDisposer = disposer
      } else {
        runtimeOwnerProjectionObservationDisposer = disposer
      }
    }
    orphanedDisposer?.invoke()
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
    val resolvedOwnerLease = writeOwnerLeaseHeartbeat() ?: return
    synchronized(lock) {
      if (keepAliveState != null) {
        currentKeepAliveState = keepAliveState
      }
      resolvedServiceLifecycle = boundServiceLifecycle ?: activeServiceLifecycle ?: return
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

  override fun tryAcquireOwnerLease(): Boolean = writeOwnerLeaseHeartbeat() != null

  private fun onServiceWorkStateChanged(workState: RuntimeServiceWorkState) {
    persistProjectionSnapshot(workState = workState)
  }

  private inline fun runCleanupPhase(phase: String, block: () -> Unit) {
    try {
      block()
    } catch (failure: Exception) {
      logCleanupPhaseFailure(phase, failure)
    }
  }

  private fun logCleanupPhaseFailure(phase: String, failure: Exception) {
    runCatching {
      Log.e(
        OWNER_LEASE_HEARTBEAT_LOG_TAG,
        "runtime.coordinatorCleanupPhaseFailure phase=$phase " +
          "type=${failure::class.java.name} message=${failure.message}",
      )
    }
  }

  private fun logOwnerLeaseStoreCorrupted(failure: RuntimeLeaseStoreCorruptedException) {
    runCatching {
      Log.e(
        OWNER_LEASE_HEARTBEAT_LOG_TAG,
        "runtime.ownerLeaseStoreCorrupted errorCode=OWNER_LEASE_STORE_CORRUPTED " +
          "file=${failure.fileName} quarantined=${failure.quarantined} " +
          "contentLength=${failure.contentLength}",
      )
    }
  }

  private fun writeOwnerLeaseHeartbeat(): RuntimeServiceOwnerLease? {
    val lease = synchronized(lock) {
      if (disposed) {
        return null
      }
      val nowEpochMs = clock()
      if (currentOwnerLease == null) {
        ownerLeaseAcquiredAtEpochMs = nowEpochMs
      }
      createOwnerLeaseLocked(nowEpochMs)
    } ?: return null
    val savedLease = try {
      ownerLeaseStore.save(lease)
    } catch (failure: RuntimeLeaseStoreCorruptedException) {
      logOwnerLeaseStoreCorrupted(failure)
      return null
    }
    val ownsLease = savedLease.sameRuntimeServiceOwnerAs(lease)
    synchronized(lock) {
      currentOwnerLease = savedLease.takeIf { ownsLease }
    }
    return savedLease.takeIf { ownsLease }
  }

  private fun createOwnerLeaseLocked(nowEpochMs: Long): RuntimeServiceOwnerLease? {
    val resolvedServiceLifecycle = boundServiceLifecycle ?: activeServiceLifecycle ?: return null
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
    val task = try {
      ownerLeaseHeartbeatScheduler.schedule(
        ownerLeaseHeartbeatIntervalMs.coerceAtLeast(0L),
      ) {
        onOwnerLeaseHeartbeat()
      }
    } catch (failure: RuntimeException) {
      val stopped = synchronized(lock) { disposed || !started }
      if (stopped) {
        return
      }
      throw failure
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
    val shouldHeartbeat = synchronized(lock) {
      ownerLeaseHeartbeatTask = null
      started && !disposed
    }
    if (!shouldHeartbeat) {
      return
    }
    try {
      persistProjectionSnapshot()
    } catch (failure: Exception) {
      runCatching {
        Log.e(
          OWNER_LEASE_HEARTBEAT_LOG_TAG,
          "runtime.ownerLeaseHeartbeatFailure type=${failure::class.java.name}",
        )
      }
    } finally {
      scheduleOwnerLeaseHeartbeat()
    }
  }

  private fun persistReleasedOwnerLeaseProjection(
    releasedLease: RuntimeServiceOwnerLease,
    projectionState: ReleasedOwnerLeaseProjectionState,
  ) {
    val serviceLifecycle = projectionState.serviceLifecycle ?: return
    val workSummary = projectionState.ownerObservationAccess.activeWorkSummary()
    projectionStore.saveSnapshot(
      RuntimeServiceProjectionSnapshot(
        localRuntimeServerState = localRuntimeServerStateProvider(),
        runtimeControllerLifecycle = runtimeControllerLifecycle,
        runtimeOwnerLifecycle = projectionState.runtimeOwnerLifecycle,
        runtimeOwnerWorkSummary = workSummary,
        serviceLifecycle = serviceLifecycle,
        serviceWorkState = RuntimeServiceWorkState(
          phase = RuntimeServiceWorkState.PHASE_IDLE,
          hasActiveWork = false,
          activeRunCount = 0,
          activeSessionCount = 0,
          pendingWorkSessionCount = 0,
          liveManagedProcessSessionCount = 0,
          liveSubAgentSessionCount = 0,
          keepAliveRequired = false,
          keepAliveReason = null,
          changedAtEpochMs = releasedLease.releasedAtEpochMs ?: releasedLease.heartbeatAtEpochMs,
          activeSinceEpochMs = null,
          idleSinceEpochMs = releasedLease.releasedAtEpochMs ?: releasedLease.heartbeatAtEpochMs,
        ),
        serviceKeepAliveState = RuntimeServiceKeepAliveState(
          phase = RuntimeServiceKeepAliveState.PHASE_DESTROYED,
          stopScheduled = false,
          stopDeadlineEpochMs = null,
          changedAtEpochMs = releasedLease.releasedAtEpochMs ?: releasedLease.heartbeatAtEpochMs,
        ),
        runtimeServiceOwnerLease = releasedLease,
        lastInterruptedRunRepair = projectionState.interruptedRunRepair,
      ),
    )
  }

  private data class ReleasedOwnerLeaseProjectionState(
    val serviceLifecycle: RuntimeServiceLifecycleDescriptor?,
    val runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor,
    val ownerObservationAccess: RuntimeOwnerObservationAccess,
    val interruptedRunRepair: RuntimeServiceInterruptedRunRepairProjection?,
  )
}

private fun defaultRuntimeServiceOwnerLeaseHeartbeatScheduler(): RuntimeServiceDelayScheduler =
  OwnerLeaseHeartbeatDelayScheduler()

private fun defaultRuntimeServiceProjectionStoreOrInMemoryDegraded(
  appContext: Context,
  runtimeTarget: RuntimeServiceTarget,
): RuntimeServiceProjectionStore =
  try {
    FileBackedRuntimeServiceProjectionStoreFactory.fromContext(appContext).create(runtimeTarget)
  } catch (failure: Exception) {
    runCatching {
      Log.e(
        OWNER_LEASE_HEARTBEAT_LOG_TAG,
        "runtime.projectionStoreDegraded durability-degraded " +
          "reason=${failure::class.java.name} message=${failure.message}",
      )
    }
    inMemoryRuntimeServiceProjectionStore()
  }

private const val OWNER_LEASE_HEARTBEAT_LOG_TAG: String = "OpenCrayRuntimeLease"

private class OwnerLeaseHeartbeatDelayScheduler(
  private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "opencray-owner-lease-heartbeat").apply {
      isDaemon = true
    }
  },
) : RuntimeServiceDelayScheduler {
  override fun schedule(
    delayMs: Long,
    action: () -> Unit,
  ): RuntimeServiceDelayedTask {
    val future = executor.schedule(
      Runnable(action),
      delayMs.coerceAtLeast(0L),
      TimeUnit.MILLISECONDS,
    )
    return RuntimeServiceDelayedTask {
      future.cancel(false)
    }
  }

  fun shutdown() {
    executor.shutdown()
  }
}
