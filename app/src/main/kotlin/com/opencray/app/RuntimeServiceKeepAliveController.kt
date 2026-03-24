package com.opencray.app

import android.os.Handler

internal data class RuntimeServiceKeepAliveState(
  val phase: String = PHASE_CREATED,
  val idleGraceMs: Long = DEFAULT_IDLE_GRACE_MS,
  val stopScheduled: Boolean = false,
  val stopDeadlineEpochMs: Long? = null,
  val lastStartId: Int? = null,
  val lastStartCommandAtEpochMs: Long? = null,
  val lastStopRequestAtEpochMs: Long? = null,
  val lastStopSucceeded: Boolean? = null,
  val changedAtEpochMs: Long = System.currentTimeMillis(),
) {
  val hasSeenStartCommand: Boolean
    get() = lastStartId != null

  fun snapshotMap(): Map<String, Any?> = buildMap {
    put("phase", phase)
    put("idleGraceMs", idleGraceMs)
    put("stopScheduled", stopScheduled)
    put("stopDeadlineEpochMs", stopDeadlineEpochMs)
    put("hasSeenStartCommand", hasSeenStartCommand)
    put("lastStartId", lastStartId)
    put("lastStartCommandAtEpochMs", lastStartCommandAtEpochMs)
    put("lastStopRequestAtEpochMs", lastStopRequestAtEpochMs)
    put("lastStopSucceeded", lastStopSucceeded)
    put("changedAtEpochMs", changedAtEpochMs)
  }

  companion object {
    const val DEFAULT_IDLE_GRACE_MS: Long = 30_000L
    const val PHASE_CREATED: String = "created"
    const val PHASE_ACTIVE_WORK: String = "active_work"
    const val PHASE_IDLE_GRACE: String = "idle_grace"
    const val PHASE_STOP_REQUESTED: String = "stop_requested"
    const val PHASE_DESTROYED: String = "destroyed"
  }
}

internal fun interface RuntimeServiceDelayedTask {
  fun cancel()
}

internal interface RuntimeServiceDelayScheduler {
  fun schedule(
    delayMs: Long,
    action: () -> Unit,
  ): RuntimeServiceDelayedTask
}

internal class HandlerRuntimeServiceDelayScheduler(
  private val handler: Handler,
) : RuntimeServiceDelayScheduler {
  override fun schedule(
    delayMs: Long,
    action: () -> Unit,
  ): RuntimeServiceDelayedTask {
    val runnable = Runnable(action)
    handler.postDelayed(runnable, delayMs)
    return RuntimeServiceDelayedTask {
      handler.removeCallbacks(runnable)
    }
  }
}

internal class RuntimeServiceKeepAliveController(
  private val idleGraceMs: Long = RuntimeServiceKeepAliveState.DEFAULT_IDLE_GRACE_MS,
  private val scheduler: RuntimeServiceDelayScheduler,
  private val stopRequester: (Int) -> Boolean,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  private val listeners = linkedSetOf<(RuntimeServiceKeepAliveState) -> Unit>()
  private var pendingStopTask: RuntimeServiceDelayedTask? = null
  private var lastObservedWorkState: RuntimeServiceWorkState = RuntimeServiceWorkState(
    changedAtEpochMs = clock(),
  )
  private var state: RuntimeServiceKeepAliveState = RuntimeServiceKeepAliveState(
    idleGraceMs = idleGraceMs,
    changedAtEpochMs = clock(),
  )
  private var destroyed: Boolean = false

  fun currentState(): RuntimeServiceKeepAliveState = synchronized(lock) { state }

  fun observe(listener: (RuntimeServiceKeepAliveState) -> Unit): () -> Unit = synchronized(lock) {
    listeners += listener
    return {
      synchronized(lock) {
        listeners -= listener
      }
    }
  }

  fun onStartCommand(startId: Int): RuntimeServiceKeepAliveState {
    val listenersToNotify: List<(RuntimeServiceKeepAliveState) -> Unit>
    val nextState: RuntimeServiceKeepAliveState
    synchronized(lock) {
      if (destroyed) {
        return state
      }
      val now = clock()
      state = state.copy(
        lastStartId = startId,
        lastStartCommandAtEpochMs = now,
        changedAtEpochMs = now,
      )
      nextState = if (lastObservedWorkState.keepAliveRequired) {
        transitionToActiveWorkLocked(now)
      } else {
        scheduleIdleStopLocked(startId = startId, now = now)
      }
      listenersToNotify = listeners.toList()
    }
    notifyListeners(listenersToNotify, nextState)
    return nextState
  }

  fun onWorkStateChanged(workState: RuntimeServiceWorkState): RuntimeServiceKeepAliveState {
    val listenersToNotify: List<(RuntimeServiceKeepAliveState) -> Unit>
    val nextState: RuntimeServiceKeepAliveState
    synchronized(lock) {
      if (destroyed) {
        return state
      }
      lastObservedWorkState = workState
      val now = clock()
      nextState = if (workState.keepAliveRequired) {
        transitionToActiveWorkLocked(now)
      } else {
        val lastStartId = state.lastStartId
        if (lastStartId == null) {
          transitionToCreatedLocked(now)
        } else if (state.phase == RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE && state.stopScheduled) {
          state
        } else {
          scheduleIdleStopLocked(startId = lastStartId, now = now)
        }
      }
      listenersToNotify = listeners.toList()
    }
    notifyListeners(listenersToNotify, nextState)
    return nextState
  }

  fun onDestroy(): RuntimeServiceKeepAliveState {
    val listenersToNotify: List<(RuntimeServiceKeepAliveState) -> Unit>
    val nextState: RuntimeServiceKeepAliveState
    synchronized(lock) {
      if (destroyed) {
        return state
      }
      destroyed = true
      cancelPendingStopLocked()
      val now = clock()
      state = state.copy(
        phase = RuntimeServiceKeepAliveState.PHASE_DESTROYED,
        stopScheduled = false,
        stopDeadlineEpochMs = null,
        changedAtEpochMs = now,
      )
      nextState = state
      listenersToNotify = listeners.toList()
    }
    notifyListeners(listenersToNotify, nextState)
    return nextState
  }

  private fun transitionToCreatedLocked(now: Long): RuntimeServiceKeepAliveState {
    cancelPendingStopLocked()
    state = state.copy(
      phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
      stopScheduled = false,
      stopDeadlineEpochMs = null,
      changedAtEpochMs = now,
    )
    return state
  }

  private fun transitionToActiveWorkLocked(now: Long): RuntimeServiceKeepAliveState {
    cancelPendingStopLocked()
    state = state.copy(
      phase = RuntimeServiceKeepAliveState.PHASE_ACTIVE_WORK,
      stopScheduled = false,
      stopDeadlineEpochMs = null,
      changedAtEpochMs = now,
    )
    return state
  }

  private fun scheduleIdleStopLocked(
    startId: Int,
    now: Long,
  ): RuntimeServiceKeepAliveState {
    cancelPendingStopLocked()
    val stopDeadlineEpochMs = now + idleGraceMs
    pendingStopTask = scheduler.schedule(idleGraceMs) {
      onIdleStopDeadlineReached(startId)
    }
    state = state.copy(
      phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
      stopScheduled = true,
      stopDeadlineEpochMs = stopDeadlineEpochMs,
      changedAtEpochMs = now,
    )
    return state
  }

  private fun onIdleStopDeadlineReached(startId: Int) {
    val listenersToNotify: List<(RuntimeServiceKeepAliveState) -> Unit>
    val nextState: RuntimeServiceKeepAliveState
    synchronized(lock) {
      if (destroyed || lastObservedWorkState.keepAliveRequired || state.lastStartId != startId) {
        return
      }
      pendingStopTask = null
      val now = clock()
      val stopSucceeded = stopRequester(startId)
      state = state.copy(
        phase = RuntimeServiceKeepAliveState.PHASE_STOP_REQUESTED,
        stopScheduled = false,
        stopDeadlineEpochMs = null,
        lastStopRequestAtEpochMs = now,
        lastStopSucceeded = stopSucceeded,
        changedAtEpochMs = now,
      )
      nextState = state
      listenersToNotify = listeners.toList()
    }
    notifyListeners(listenersToNotify, nextState)
  }

  private fun cancelPendingStopLocked() {
    pendingStopTask?.cancel()
    pendingStopTask = null
  }

  private fun notifyListeners(
    listeners: List<(RuntimeServiceKeepAliveState) -> Unit>,
    state: RuntimeServiceKeepAliveState,
  ) {
    if (listeners.isEmpty()) {
      return
    }
    listeners.forEach { listener -> listener(state) }
  }
}
