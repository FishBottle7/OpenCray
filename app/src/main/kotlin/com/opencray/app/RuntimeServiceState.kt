package com.opencray.app

internal data class RuntimeControllerLifecycleDescriptor(
  val processStartId: String = OpenCrayProcessLifecycle.processStartId,
  val processStartedAtEpochMs: Long = OpenCrayProcessLifecycle.processStartedAtEpochMs,
  val controllerInstanceId: String = lifecycleId(prefix = "runtime-controller"),
  val controllerCreatedAtEpochMs: Long = System.currentTimeMillis(),
) {
  fun snapshotMap(): Map<String, Any?> = mapOf(
    "processStartId" to processStartId,
    "processStartedAtEpochMs" to processStartedAtEpochMs,
    "controllerInstanceId" to controllerInstanceId,
    "controllerCreatedAtEpochMs" to controllerCreatedAtEpochMs,
  )
}

internal data class RuntimeServiceLifecycleDescriptor(
  val processStartId: String = OpenCrayProcessLifecycle.processStartId,
  val processStartedAtEpochMs: Long = OpenCrayProcessLifecycle.processStartedAtEpochMs,
  val serviceInstanceId: String = lifecycleId(prefix = "runtime-service"),
  val serviceCreatedAtEpochMs: Long = System.currentTimeMillis(),
  val serviceProcess: RuntimeServiceProcessDescriptor? = null,
) {
  fun snapshotMap(): Map<String, Any?> = buildMap {
    put("processStartId", processStartId)
    put("processStartedAtEpochMs", processStartedAtEpochMs)
    put("serviceInstanceId", serviceInstanceId)
    put("serviceCreatedAtEpochMs", serviceCreatedAtEpochMs)
    serviceProcess?.let { descriptor ->
      put("serviceProcess", descriptor.snapshotMap())
    }
  }
}

internal data class RuntimeReplayExecutionContext(
  val executionId: String? = null,
  val executionOrdinal: Int? = null,
  val executionKind: String? = null,
)

internal data class RuntimeOwnerWorkSummary(
  val trackedSessionCount: Int = 0,
  val activeRunCount: Int = 0,
  val activeSessionIds: List<String> = emptyList(),
  val pendingWorkSessionIds: List<String> = emptyList(),
  val liveManagedProcessSessionIds: List<String> = emptyList(),
  val liveSubAgentSessionIds: List<String> = emptyList(),
) {
  val hasActiveWork: Boolean
    get() = activeSessionIds.isNotEmpty() || activeRunCount > 0

  fun snapshotMap(): Map<String, Any?> = mapOf(
    "hasActiveWork" to hasActiveWork,
    "trackedSessionCount" to trackedSessionCount,
    "activeRunCount" to activeRunCount,
    "activeSessionCount" to activeSessionIds.size,
    "activeSessionIds" to activeSessionIds,
    "pendingWorkSessionIds" to pendingWorkSessionIds,
    "liveManagedProcessSessionIds" to liveManagedProcessSessionIds,
    "liveSubAgentSessionIds" to liveSubAgentSessionIds,
  )
}

internal data class RuntimeServiceWorkState(
  val phase: String = PHASE_IDLE,
  val hasActiveWork: Boolean = false,
  val activeRunCount: Int = 0,
  val activeSessionCount: Int = 0,
  val pendingWorkSessionCount: Int = 0,
  val liveManagedProcessSessionCount: Int = 0,
  val liveSubAgentSessionCount: Int = 0,
  val keepAliveRequired: Boolean = false,
  val keepAliveReason: String? = null,
  val changedAtEpochMs: Long = System.currentTimeMillis(),
  val activeSinceEpochMs: Long? = null,
  val idleSinceEpochMs: Long? = changedAtEpochMs,
) {
  fun snapshotMap(): Map<String, Any?> = buildMap {
    put("phase", phase)
    put("hasActiveWork", hasActiveWork)
    put("activeRunCount", activeRunCount)
    put("activeSessionCount", activeSessionCount)
    put("pendingWorkSessionCount", pendingWorkSessionCount)
    put("liveManagedProcessSessionCount", liveManagedProcessSessionCount)
    put("liveSubAgentSessionCount", liveSubAgentSessionCount)
    put("keepAliveRequired", keepAliveRequired)
    keepAliveReason?.let { reason ->
      put("keepAliveReason", reason)
    }
    put("changedAtEpochMs", changedAtEpochMs)
    put("activeSinceEpochMs", activeSinceEpochMs)
    put("idleSinceEpochMs", idleSinceEpochMs)
  }

  companion object {
    const val PHASE_IDLE: String = "idle"
    const val PHASE_ACTIVE_WORK: String = "active_work"
    const val KEEP_ALIVE_REASON_ACTIVE_RUN: String = "active_run"
    const val KEEP_ALIVE_REASON_MANAGED_PROCESS: String = "managed_process"
    const val KEEP_ALIVE_REASON_ACTIVE_SUBAGENT: String = "active_subagent"
    const val KEEP_ALIVE_REASON_IDLE_GRACE: String = "idle_grace"
    const val KEEP_ALIVE_REASON_SERVICE_STARTUP: String = "service_startup"
  }
}

internal class RuntimeServiceWorkStateTracker(
  private val workSummaryProvider: () -> RuntimeOwnerWorkSummary,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  private val listeners = linkedSetOf<(RuntimeServiceWorkState) -> Unit>()
  private var currentState: RuntimeServiceWorkState = RuntimeServiceWorkState(
    changedAtEpochMs = clock(),
  )

  fun currentState(): RuntimeServiceWorkState = synchronized(lock) { currentState }

  fun observe(listener: (RuntimeServiceWorkState) -> Unit): () -> Unit = synchronized(lock) {
    listeners += listener
    return {
      synchronized(lock) {
        listeners -= listener
      }
    }
  }

  fun refresh(): RuntimeServiceWorkState {
    val listenersToNotify: List<(RuntimeServiceWorkState) -> Unit>
    val nextState: RuntimeServiceWorkState
    synchronized(lock) {
      val summary = workSummaryProvider()
      val nextPhase = if (summary.hasActiveWork) {
        RuntimeServiceWorkState.PHASE_ACTIVE_WORK
      } else {
        RuntimeServiceWorkState.PHASE_IDLE
      }
      val nextReason = when {
        summary.liveManagedProcessSessionIds.isNotEmpty() ->
          RuntimeServiceWorkState.KEEP_ALIVE_REASON_MANAGED_PROCESS
        summary.liveSubAgentSessionIds.isNotEmpty() ->
          RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_SUBAGENT
        summary.pendingWorkSessionIds.isNotEmpty() || summary.activeRunCount > 0 ->
          RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_RUN
        else -> null
      }
      val previous = currentState
      if (
        previous.phase == nextPhase &&
        previous.activeRunCount == summary.activeRunCount &&
        previous.activeSessionCount == summary.activeSessionIds.size &&
        previous.pendingWorkSessionCount == summary.pendingWorkSessionIds.size &&
        previous.liveManagedProcessSessionCount == summary.liveManagedProcessSessionIds.size &&
        previous.liveSubAgentSessionCount == summary.liveSubAgentSessionIds.size &&
        previous.keepAliveRequired == summary.hasActiveWork &&
        previous.keepAliveReason == nextReason
      ) {
        return previous
      }
      val changedAtEpochMs = clock()
      currentState = if (summary.hasActiveWork) {
        RuntimeServiceWorkState(
          phase = nextPhase,
          hasActiveWork = true,
          activeRunCount = summary.activeRunCount,
          activeSessionCount = summary.activeSessionIds.size,
          pendingWorkSessionCount = summary.pendingWorkSessionIds.size,
          liveManagedProcessSessionCount = summary.liveManagedProcessSessionIds.size,
          liveSubAgentSessionCount = summary.liveSubAgentSessionIds.size,
          keepAliveRequired = true,
          keepAliveReason = nextReason,
          changedAtEpochMs = changedAtEpochMs,
          activeSinceEpochMs = if (previous.phase == RuntimeServiceWorkState.PHASE_ACTIVE_WORK) {
            previous.activeSinceEpochMs ?: changedAtEpochMs
          } else {
            changedAtEpochMs
          },
          idleSinceEpochMs = null,
        )
      } else {
        RuntimeServiceWorkState(
          phase = nextPhase,
          hasActiveWork = false,
          activeRunCount = summary.activeRunCount,
          activeSessionCount = summary.activeSessionIds.size,
          pendingWorkSessionCount = summary.pendingWorkSessionIds.size,
          liveManagedProcessSessionCount = summary.liveManagedProcessSessionIds.size,
          liveSubAgentSessionCount = summary.liveSubAgentSessionIds.size,
          keepAliveRequired = false,
          keepAliveReason = null,
          changedAtEpochMs = changedAtEpochMs,
          activeSinceEpochMs = null,
          idleSinceEpochMs = if (previous.phase == RuntimeServiceWorkState.PHASE_IDLE) {
            previous.idleSinceEpochMs ?: changedAtEpochMs
          } else {
            changedAtEpochMs
          },
        )
      }
      nextState = currentState
      listenersToNotify = listeners.toList()
    }
    notifyListeners(listenersToNotify, nextState)
    return nextState
  }

  private fun notifyListeners(
    listeners: List<(RuntimeServiceWorkState) -> Unit>,
    state: RuntimeServiceWorkState,
  ) {
    if (listeners.isEmpty()) {
      return
    }
    listeners.forEach { listener -> listener(state) }
  }
}
