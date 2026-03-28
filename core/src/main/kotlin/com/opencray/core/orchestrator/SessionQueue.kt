package com.opencray.core.orchestrator

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ContractSchemaVersion
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class SessionLifecycleState {
  IDLE,
  RUNNING,
  STOPPED,
}

@Serializable
enum class QueueTaskLifecycleState {
  QUEUED,
  RUNNING,
  RETRY_PENDING,
  SUSPENDED,
  CANCEL_REQUESTED,
  COMPLETED,
  FAILED,
  CANCELLED,
}

@Serializable
data class SessionQueueTaskSnapshot(
  val enqueueOrder: Long,
  val task: AgentTask,
  val lifecycleState: QueueTaskLifecycleState = QueueTaskLifecycleState.QUEUED,
  val attempt: Int = 0,
  val executionOrdinal: Int = 0,
  val executionId: String? = null,
  val executionKind: String? = null,
  val lastErrorCode: String? = null,
  val lastErrorMessage: String? = null,
) {
  init {
    require(enqueueOrder >= 0) { "SessionQueueTaskSnapshot enqueueOrder must be >= 0." }
    require(attempt >= 0) { "SessionQueueTaskSnapshot attempt must be >= 0." }
    require(executionOrdinal >= 0) {
      "SessionQueueTaskSnapshot executionOrdinal must be >= 0."
    }
  }
}

@Serializable
data class SessionQueueSnapshot(
  val sessionId: String,
  val agentId: String,
  val lifecycleState: SessionLifecycleState = SessionLifecycleState.IDLE,
  val nextEnqueueOrder: Long = 1,
  val tasks: List<SessionQueueTaskSnapshot> = emptyList(),
  val updatedAtEpochMs: Long,
  val schemaVersion: Int = ContractSchemaVersion.CURRENT,
) {
  init {
    require(sessionId.isNotBlank()) { "SessionQueueSnapshot sessionId must not be blank." }
    require(agentId.isNotBlank()) { "SessionQueueSnapshot agentId must not be blank." }
    require(nextEnqueueOrder >= 1) { "SessionQueueSnapshot nextEnqueueOrder must be >= 1." }
  }
}

interface SessionQueueSnapshotStore {
  fun load(): SessionQueueSnapshot?
  fun save(snapshot: SessionQueueSnapshot)
  fun clear()
}

class InMemorySessionQueueSnapshotStore(
  initialSnapshot: SessionQueueSnapshot? = null,
) : SessionQueueSnapshotStore {
  private var snapshot: SessionQueueSnapshot? = initialSnapshot

  override fun load(): SessionQueueSnapshot? = snapshot

  override fun save(snapshot: SessionQueueSnapshot) {
    this.snapshot = snapshot
  }

  override fun clear() {
    snapshot = null
  }
}

interface QueueClock {
  fun nowEpochMs(): Long
}

object SystemQueueClock : QueueClock {
  override fun nowEpochMs(): Long = System.currentTimeMillis()
}

data class RetryRequest(
  val reasonCode: String,
  val detail: String? = null,
) {
  init {
    require(reasonCode.isNotBlank()) { "RetryRequest reasonCode must not be blank." }
  }
}

data class SuspensionRequest(
  val reasonCode: String,
  val detail: String? = null,
) {
  init {
    require(reasonCode.isNotBlank()) { "SuspensionRequest reasonCode must not be blank." }
  }
}

data class RuntimeExecutionHooks(
  val isCancellationRequested: () -> Boolean,
  val requestRetry: (RetryRequest) -> Unit,
  val requestSuspend: (SuspensionRequest) -> Unit = {},
)

fun interface SessionTaskRuntime {
  fun execute(task: AgentTask, hooks: RuntimeExecutionHooks): ExecutionResult
}

data class SessionQueueConfig(
  val maxAttempts: Int = 3,
) {
  init {
    require(maxAttempts >= 1) { "SessionQueueConfig maxAttempts must be >= 1." }
  }
}

const val ERROR_RESTART_REQUIRES_EXPLICIT_RETRY: String = "RESTART_REQUIRES_EXPLICIT_RETRY"
const val METADATA_QUEUE_RESTORE_EPOCH_MS: String = "_queue.restoreEpochMs"
const val METADATA_PREVIOUS_LIFECYCLE_STATE: String = "_queue.previousLifecycleState"
const val METADATA_RECOVERY_REASON: String = "_queue.recoveryReason"
const val METADATA_EXECUTION_ID: String = "_host.executionId"
const val METADATA_EXECUTION_KIND: String = "_host.executionKind"
const val METADATA_EXECUTION_ORDINAL: String = "_host.executionOrdinal"
const val METADATA_PENDING_EXECUTION_KIND: String = "_host.pendingExecutionKind"
const val EXECUTION_KIND_INITIAL: String = "initial"
const val EXECUTION_KIND_RETRY: String = "retry"
const val EXECUTION_KIND_APPROVAL_RESUME: String = "approval_resume"
const val EXECUTION_KIND_CHECKPOINT_RESUME: String = "checkpoint_resume"
const val RECOVERY_REASON_HOST_RESTART_INFLIGHT_TASK_INTERRUPTED: String =
  "host_restart_inflight_task_interrupted"

class SessionQueue(
  private val sessionId: String,
  private val agentId: String,
  private val runtime: SessionTaskRuntime,
  private val snapshotStore: SessionQueueSnapshotStore,
  private val clock: QueueClock = SystemQueueClock,
  private val config: SessionQueueConfig = SessionQueueConfig(),
) {
  private val lock = Any()
  private val taskEntries = mutableListOf<SessionQueueTaskSnapshot>()
  private var lifecycleState: SessionLifecycleState = SessionLifecycleState.IDLE
  private var nextEnqueueOrder: Long = 1L

  init {
    require(sessionId.isNotBlank()) { "SessionQueue sessionId must not be blank." }
    require(agentId.isNotBlank()) { "SessionQueue agentId must not be blank." }

    synchronized(lock) {
      restoreLocked(snapshotStore.load())
      persistSnapshotLocked()
    }
  }

  fun enqueue(task: AgentTask): AgentTask = synchronized(lock) {
    require(task.id.isNotBlank()) { "Queued task id must not be blank." }
    require(taskEntries.none { it.task.id == task.id }) {
      "Task id already exists in session queue: ${task.id}"
    }

    val now = clock.nowEpochMs()
    val queuedTask = task.copy(
      state = AgentTaskState.QUEUED,
      updatedAtEpochMs = maxOf(now, task.createdAtEpochMs),
      metadata = task.metadata + mapOf(
        METADATA_PENDING_EXECUTION_KIND to EXECUTION_KIND_INITIAL,
      ),
    )

    taskEntries += SessionQueueTaskSnapshot(
      enqueueOrder = nextEnqueueOrder,
      task = queuedTask,
      lifecycleState = QueueTaskLifecycleState.QUEUED,
      attempt = 0,
    )
    nextEnqueueOrder += 1
    persistSnapshotLocked()
    return queuedTask
  }

  /**
   * Executes up to [maxTasks] runtime calls, always serial and FIFO by enqueue order.
   */
  fun drain(maxTasks: Int = Int.MAX_VALUE): List<ExecutionResult> {
    require(maxTasks >= 0) { "drain maxTasks must be >= 0." }
    synchronized(lock) {
      if (maxTasks == 0 || lifecycleState == SessionLifecycleState.STOPPED) {
        return emptyList()
      }
      transitionSessionStateLocked(SessionLifecycleState.RUNNING)
    }
    val results = mutableListOf<ExecutionResult>()
    var executedCount = 0

    while (executedCount < maxTasks) {
      val nextIndex = synchronized(lock) {
        if (lifecycleState == SessionLifecycleState.STOPPED) {
          null
        } else {
          nextRunnableTaskIndexLocked()
        }
      }
      if (nextIndex == null) break

      val result = executeTaskAt(nextIndex)
      if (result != null) {
        results += result
        executedCount += 1
      }
    }

    synchronized(lock) {
      if (lifecycleState != SessionLifecycleState.STOPPED) {
        transitionSessionStateLocked(SessionLifecycleState.IDLE)
      }
    }

    return results
  }

  /**
   * Cancellation hook exposed to downstream runtime invocations.
   *
   * - QUEUED/RETRY_PENDING/SUSPENDED tasks are cancelled before execution.
   * - RUNNING tasks transition to CANCEL_REQUESTED and runtime can observe this via hooks.
   */
  fun requestCancel(taskId: String): Boolean = synchronized(lock) {
    val index = indexOfTaskLocked(taskId) ?: return false
    val current = taskEntries[index]

    return when (current.lifecycleState) {
      QueueTaskLifecycleState.QUEUED,
      QueueTaskLifecycleState.RETRY_PENDING,
      QueueTaskLifecycleState.SUSPENDED,
      -> {
        transitionTaskLocked(index, QueueTaskLifecycleState.CANCELLED)
        true
      }

      QueueTaskLifecycleState.RUNNING -> {
        transitionTaskLocked(index, QueueTaskLifecycleState.CANCEL_REQUESTED)
        true
      }

      QueueTaskLifecycleState.CANCEL_REQUESTED -> true
      QueueTaskLifecycleState.COMPLETED,
      QueueTaskLifecycleState.FAILED,
      QueueTaskLifecycleState.CANCELLED,
      -> false
    }
  }

  /**
   * Manual retry hook for failed tasks, keeping deterministic serial ordering.
   */
  fun requestRetry(taskId: String): Boolean = synchronized(lock) {
    val index = indexOfTaskLocked(taskId) ?: return false
    val current = taskEntries[index]
    if (current.lifecycleState != QueueTaskLifecycleState.FAILED) return false
    val restartInterrupted = current.lastErrorCode == ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
    if (!restartInterrupted && current.attempt >= config.maxAttempts) return false

    markPendingExecutionKindLocked(index, EXECUTION_KIND_RETRY)
    transitionTaskLocked(index, QueueTaskLifecycleState.RETRY_PENDING)
    transitionTaskLocked(
      index = index,
      to = QueueTaskLifecycleState.QUEUED,
      clearExecutionContext = true,
    )
    return true
  }

  /**
   * Resume a task that is explicitly suspended, for example while awaiting approval.
   */
  fun requestResumeTask(taskId: String): Boolean =
    requestResumeTask(
      taskId = taskId,
      executionKind = EXECUTION_KIND_APPROVAL_RESUME,
    )

  fun requestResumeTask(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String> = emptyMap(),
  ): Boolean = synchronized(lock) {
    val index = indexOfTaskLocked(taskId) ?: return false
    val current = taskEntries[index]
    if (current.lifecycleState != QueueTaskLifecycleState.SUSPENDED) return false

    require(
      executionKind == EXECUTION_KIND_APPROVAL_RESUME ||
        executionKind == EXECUTION_KIND_CHECKPOINT_RESUME,
    ) {
      "Unsupported resume execution kind: $executionKind"
    }

    if (taskMetadataUpdates.isNotEmpty()) {
      applyTaskMetadataUpdatesLocked(index = index, updates = taskMetadataUpdates)
    }
    markPendingExecutionKindLocked(index, executionKind)
    transitionTaskLocked(
      index = index,
      to = QueueTaskLifecycleState.QUEUED,
      clearExecutionContext = true,
    )
    return true
  }

  fun stop(): SessionLifecycleState = synchronized(lock) {
    transitionSessionStateLocked(SessionLifecycleState.STOPPED)
    return lifecycleState
  }

  fun resume(): SessionLifecycleState = synchronized(lock) {
    if (lifecycleState == SessionLifecycleState.STOPPED) {
      transitionSessionStateLocked(SessionLifecycleState.IDLE)
    }
    return lifecycleState
  }

  fun currentSessionState(): SessionLifecycleState = synchronized(lock) { lifecycleState }

  fun snapshot(): SessionQueueSnapshot = synchronized(lock) { buildSnapshotLocked() }

  private fun restoreLocked(snapshot: SessionQueueSnapshot?) {
    if (snapshot == null) return
    if (snapshot.sessionId != sessionId || snapshot.agentId != agentId) return

    lifecycleState =
      if (snapshot.lifecycleState == SessionLifecycleState.STOPPED) {
        SessionLifecycleState.STOPPED
      } else {
        SessionLifecycleState.IDLE
      }

    val restoreEpochMs = clock.nowEpochMs()
    val restored = snapshot.tasks
      .sortedBy { it.enqueueOrder }
      .map { entry -> normalizeAfterRestart(entry, restoreEpochMs) }

    taskEntries.clear()
    taskEntries += restored

    val maxOrder = restored.maxOfOrNull { it.enqueueOrder } ?: 0L
    nextEnqueueOrder = maxOf(snapshot.nextEnqueueOrder, maxOrder + 1)
  }

  private fun normalizeAfterRestart(
    entry: SessionQueueTaskSnapshot,
    restoreEpochMs: Long,
  ): SessionQueueTaskSnapshot {
    val wasInterruptedInFlight = when (entry.lifecycleState) {
      QueueTaskLifecycleState.RUNNING,
      QueueTaskLifecycleState.CANCEL_REQUESTED,
      QueueTaskLifecycleState.RETRY_PENDING,
      -> true

      else -> false
    }
    val normalizedLifecycle = if (wasInterruptedInFlight) {
      QueueTaskLifecycleState.FAILED
    } else {
      entry.lifecycleState
    }
    val mappedTaskState = mapLifecycleToAgentTaskState(normalizedLifecycle)
    val preservedRestoreEpochMs = entry.task.metadata[METADATA_QUEUE_RESTORE_EPOCH_MS]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val preservedPreviousLifecycleState = entry.task.metadata[METADATA_PREVIOUS_LIFECYCLE_STATE]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val preservedRecoveryReason = entry.task.metadata[METADATA_RECOVERY_REASON]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val normalizedMetadata = buildMap<String, String> {
      putAll(
        if (wasInterruptedInFlight) {
          entry.task.metadata.filterKeys { key ->
            key != METADATA_QUEUE_RESTORE_EPOCH_MS &&
              key != METADATA_PREVIOUS_LIFECYCLE_STATE &&
              key != METADATA_RECOVERY_REASON
          }
        } else {
          entry.task.metadata
        },
      )
      if (wasInterruptedInFlight) {
        put(METADATA_QUEUE_RESTORE_EPOCH_MS, restoreEpochMs.toString())
        put(METADATA_PREVIOUS_LIFECYCLE_STATE, entry.lifecycleState.name.lowercase())
        put(METADATA_RECOVERY_REASON, RECOVERY_REASON_HOST_RESTART_INFLIGHT_TASK_INTERRUPTED)
      } else {
        put(
          METADATA_QUEUE_RESTORE_EPOCH_MS,
          preservedRestoreEpochMs ?: restoreEpochMs.toString(),
        )
        put(
          METADATA_PREVIOUS_LIFECYCLE_STATE,
          preservedPreviousLifecycleState ?: entry.lifecycleState.name.lowercase(),
        )
        preservedRecoveryReason?.let { reason ->
          put(METADATA_RECOVERY_REASON, reason)
        }
      }
    }
    val normalizedTask = entry.task.copy(
      state = mappedTaskState,
      updatedAtEpochMs = maxOf(
        entry.task.updatedAtEpochMs,
        entry.task.createdAtEpochMs,
        restoreEpochMs,
      ),
      metadata = normalizedMetadata,
    )

    return entry.copy(
      lifecycleState = normalizedLifecycle,
      task = normalizedTask,
      lastErrorCode = if (wasInterruptedInFlight) {
        ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
      } else {
        entry.lastErrorCode
      },
      lastErrorMessage = if (wasInterruptedInFlight) {
        buildRestoreInterruptedMessage(entry.lifecycleState)
      } else {
        entry.lastErrorMessage
      },
    )
  }

  private fun buildRestoreInterruptedMessage(
    previousState: QueueTaskLifecycleState,
  ): String = when (previousState) {
    QueueTaskLifecycleState.RUNNING ->
      "The app host restarted while this run was in progress. OpenCray stopped it to avoid silently rerunning from the beginning. Retry explicitly when you want to continue."

    QueueTaskLifecycleState.RETRY_PENDING ->
      "The app host restarted while this run was waiting to retry. OpenCray stopped it to avoid silently resuming work. Retry explicitly when you want to continue."

    QueueTaskLifecycleState.CANCEL_REQUESTED ->
      "The app host restarted while cancellation was still settling. OpenCray stopped the run and now requires an explicit retry to continue."

    else ->
      "The app host restarted before this run could finish. OpenCray stopped it to avoid silently rerunning from the beginning. Retry explicitly when you want to continue."
  }

  private fun executeTaskAt(index: Int): ExecutionResult? {
    val runningSnapshot = synchronized(lock) {
      val preRun = taskEntries[index]
      if (preRun.lifecycleState == QueueTaskLifecycleState.RETRY_PENDING) {
        transitionTaskLocked(
          index = index,
          to = QueueTaskLifecycleState.QUEUED,
          clearExecutionContext = true,
        )
      }

      val current = taskEntries[index]
      if (current.lifecycleState != QueueTaskLifecycleState.QUEUED) {
        return null
      }

      transitionTaskLocked(index, QueueTaskLifecycleState.RUNNING)
      taskEntries[index]
    }

    var retryRequest: RetryRequest? = null
    var suspensionRequest: SuspensionRequest? = null
    val hooks = RuntimeExecutionHooks(
      isCancellationRequested = {
        synchronized(lock) {
          taskEntries.getOrNull(index)?.lifecycleState == QueueTaskLifecycleState.CANCEL_REQUESTED
        }
      },
      requestRetry = { request -> retryRequest = request },
      requestSuspend = { request -> suspensionRequest = request },
    )

    val normalizedResult = executeRuntimeSafely(runningSnapshot.task, hooks)
    synchronized(lock) {
      val latest = taskEntries[index]
      val shouldRetry =
        retryRequest != null &&
          normalizedResult.status != ExecutionStatus.SUCCESS &&
          normalizedResult.status != ExecutionStatus.CANCELLED &&
          latest.attempt < config.maxAttempts

      if (suspensionRequest != null &&
        latest.lifecycleState != QueueTaskLifecycleState.CANCEL_REQUESTED &&
        normalizedResult.status != ExecutionStatus.SUCCESS &&
        normalizedResult.status != ExecutionStatus.CANCELLED
      ) {
        transitionTaskLocked(
          index = index,
          to = QueueTaskLifecycleState.SUSPENDED,
          errorCode = normalizedResult.errorCode ?: suspensionRequest?.reasonCode,
          errorMessage = normalizedResult.errorMessage ?: suspensionRequest?.detail,
        )
        return normalizedResult
      }

      if (shouldRetry) {
        val request = retryRequest!!
        markPendingExecutionKindLocked(index, EXECUTION_KIND_RETRY)
        transitionTaskLocked(
          index = index,
          to = QueueTaskLifecycleState.RETRY_PENDING,
          errorCode = request.reasonCode,
          errorMessage = request.detail ?: normalizedResult.errorMessage,
        )
        transitionTaskLocked(
          index = index,
          to = QueueTaskLifecycleState.QUEUED,
          clearExecutionContext = true,
        )
        return normalizedResult
      }

      when {
        latest.lifecycleState == QueueTaskLifecycleState.CANCEL_REQUESTED ||
          normalizedResult.status == ExecutionStatus.CANCELLED -> {
          transitionTaskLocked(
            index = index,
            to = QueueTaskLifecycleState.CANCELLED,
            errorCode = normalizedResult.errorCode,
            errorMessage = normalizedResult.errorMessage,
          )
        }

        normalizedResult.status == ExecutionStatus.SUCCESS -> {
          transitionTaskLocked(index, QueueTaskLifecycleState.COMPLETED)
        }

        else -> {
          transitionTaskLocked(
            index = index,
            to = QueueTaskLifecycleState.FAILED,
            errorCode = normalizedResult.errorCode,
            errorMessage = normalizedResult.errorMessage,
          )
        }
      }
    }

    return normalizedResult
  }

  private fun executeRuntimeSafely(task: AgentTask, hooks: RuntimeExecutionHooks): ExecutionResult {
    return try {
      val result = runtime.execute(task, hooks)
      result.copy(
        taskId = task.id,
        startedAtEpochMs = minOf(result.startedAtEpochMs, result.finishedAtEpochMs),
        finishedAtEpochMs = maxOf(result.startedAtEpochMs, result.finishedAtEpochMs),
      )
    } catch (throwable: Throwable) {
      val startedAt = clock.nowEpochMs()
      val finishedAt = clock.nowEpochMs()
      ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.FAILED,
        errorCode = "RUNTIME_EXCEPTION",
        errorMessage = throwable.message ?: throwable::class.java.simpleName,
        startedAtEpochMs = minOf(startedAt, finishedAt),
        finishedAtEpochMs = maxOf(startedAt, finishedAt),
      )
    }
  }

  private fun transitionTaskLocked(
    index: Int,
    to: QueueTaskLifecycleState,
    errorCode: String? = null,
    errorMessage: String? = null,
    clearExecutionContext: Boolean = false,
  ) {
    val current = taskEntries[index]
    val allowed = ALLOWED_TASK_TRANSITIONS.getValue(current.lifecycleState)
    require(to == current.lifecycleState || to in allowed) {
      "Invalid task transition for ${current.task.id}: ${current.lifecycleState} -> $to"
    }

    val now = clock.nowEpochMs()
    val nextExecutionKind = if (to == QueueTaskLifecycleState.RUNNING) {
      resolvedExecutionKindForRun(current)
    } else {
      null
    }
    val nextAttempt = if (to == QueueTaskLifecycleState.RUNNING) {
      nextAttemptForRun(
        currentAttempt = current.attempt,
        executionKind = requireNotNull(nextExecutionKind),
      )
    } else {
      current.attempt
    }
    val nextExecutionOrdinal = if (to == QueueTaskLifecycleState.RUNNING) {
      current.executionOrdinal + 1
    } else {
      current.executionOrdinal
    }
    val nextExecutionId = if (to == QueueTaskLifecycleState.RUNNING) {
      queueExecutionId(
        taskId = current.task.id,
        executionOrdinal = nextExecutionOrdinal,
        nowEpochMs = now,
      )
    } else if (clearExecutionContext) {
      null
    } else {
      current.executionId
    }
    val retainedExecutionKind = when {
      to == QueueTaskLifecycleState.RUNNING -> nextExecutionKind
      clearExecutionContext -> null
      else -> current.executionKind
    }
    val nextTask = current.task.copy(
      state = mapLifecycleToAgentTaskState(to),
      updatedAtEpochMs = maxOf(now, current.task.createdAtEpochMs),
      metadata = nextTaskMetadata(
        current = current,
        to = to,
        executionId = nextExecutionId,
        executionOrdinal = nextExecutionOrdinal,
        executionKind = retainedExecutionKind,
        clearExecutionContext = clearExecutionContext,
      ),
    )

    taskEntries[index] = current.copy(
      lifecycleState = to,
      task = nextTask,
      attempt = nextAttempt,
      executionOrdinal = nextExecutionOrdinal,
      executionId = nextExecutionId,
      executionKind = retainedExecutionKind,
      lastErrorCode = errorCode ?: current.lastErrorCode,
      lastErrorMessage = errorMessage ?: current.lastErrorMessage,
    )
    persistSnapshotLocked()
  }

  private fun markPendingExecutionKindLocked(index: Int, executionKind: String) {
    val current = taskEntries[index]
    taskEntries[index] = current.copy(
      task = current.task.copy(
        metadata = current.task.metadata + mapOf(
          METADATA_PENDING_EXECUTION_KIND to executionKind,
        ),
      ),
    )
    persistSnapshotLocked()
  }

  private fun applyTaskMetadataUpdatesLocked(index: Int, updates: Map<String, String>) {
    if (updates.isEmpty()) {
      return
    }
    val current = taskEntries[index]
    val normalizedUpdates = updates.filterKeys { key -> key.isNotBlank() }
    if (normalizedUpdates.isEmpty()) {
      return
    }
    taskEntries[index] = current.copy(
      task = current.task.copy(
        updatedAtEpochMs = maxOf(clock.nowEpochMs(), current.task.createdAtEpochMs),
        metadata = current.task.metadata + normalizedUpdates,
      ),
    )
    persistSnapshotLocked()
  }

  private fun nextTaskMetadata(
    current: SessionQueueTaskSnapshot,
    to: QueueTaskLifecycleState,
    executionId: String?,
    executionOrdinal: Int,
    executionKind: String?,
    clearExecutionContext: Boolean,
  ): Map<String, String> = buildMap {
    current.task.metadata.forEach { (key, value) ->
      if (
        key != METADATA_EXECUTION_ID &&
        key != METADATA_EXECUTION_KIND &&
        key != METADATA_EXECUTION_ORDINAL &&
        key != METADATA_PENDING_EXECUTION_KIND
      ) {
        put(key, value)
      }
    }
    if (to == QueueTaskLifecycleState.RUNNING) {
      executionId?.let { put(METADATA_EXECUTION_ID, it) }
      executionKind?.let { put(METADATA_EXECUTION_KIND, it) }
      put(METADATA_EXECUTION_ORDINAL, executionOrdinal.toString())
    } else if (!clearExecutionContext) {
      current.executionId?.let { put(METADATA_EXECUTION_ID, it) }
      current.executionKind?.let { put(METADATA_EXECUTION_KIND, it) }
      if (current.executionOrdinal > 0) {
        put(METADATA_EXECUTION_ORDINAL, current.executionOrdinal.toString())
      }
      current.task.metadata[METADATA_PENDING_EXECUTION_KIND]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { pendingKind ->
          put(METADATA_PENDING_EXECUTION_KIND, pendingKind)
        }
    } else if (executionOrdinal > 0) {
      put(METADATA_EXECUTION_ORDINAL, executionOrdinal.toString())
      current.task.metadata[METADATA_PENDING_EXECUTION_KIND]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { pendingKind ->
          put(METADATA_PENDING_EXECUTION_KIND, pendingKind)
        }
    }
  }

  private fun resolvedExecutionKindForRun(
    current: SessionQueueTaskSnapshot,
  ): String {
    val pendingKind = current.task.metadata[METADATA_PENDING_EXECUTION_KIND]
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotBlank)
    return when (pendingKind) {
      EXECUTION_KIND_INITIAL,
      EXECUTION_KIND_RETRY,
      EXECUTION_KIND_APPROVAL_RESUME,
      EXECUTION_KIND_CHECKPOINT_RESUME,
      -> pendingKind

      else -> if (current.executionOrdinal == 0) {
        EXECUTION_KIND_INITIAL
      } else {
        EXECUTION_KIND_RETRY
      }
    }
  }

  private fun nextAttemptForRun(
    currentAttempt: Int,
    executionKind: String,
  ): Int = when (executionKind) {
    EXECUTION_KIND_INITIAL -> maxOf(currentAttempt, 1)
    EXECUTION_KIND_RETRY -> currentAttempt + 1
    EXECUTION_KIND_APPROVAL_RESUME,
    EXECUTION_KIND_CHECKPOINT_RESUME,
    -> maxOf(currentAttempt, 1)

    else -> maxOf(currentAttempt, 1)
  }

  private fun queueExecutionId(
    taskId: String,
    executionOrdinal: Int,
    nowEpochMs: Long,
  ): String = buildString {
    append("exec-")
    append(taskId.take(24))
    append('-')
    append(executionOrdinal)
    append('-')
    append(nowEpochMs)
    append('-')
    append(UUID.randomUUID().toString().take(8))
  }

  private fun transitionSessionStateLocked(to: SessionLifecycleState) {
    if (lifecycleState == to) return
    require(to in ALLOWED_SESSION_TRANSITIONS.getValue(lifecycleState)) {
      "Invalid session transition: $lifecycleState -> $to"
    }

    lifecycleState = to
    persistSnapshotLocked()
  }

  private fun nextRunnableTaskIndexLocked(): Int? {
    val sorted = taskEntries.indices.sortedBy { taskEntries[it].enqueueOrder }
    for (index in sorted) {
      val state = taskEntries[index].lifecycleState
      if (state == QueueTaskLifecycleState.QUEUED || state == QueueTaskLifecycleState.RETRY_PENDING) {
        return index
      }
    }
    return null
  }

  private fun indexOfTaskLocked(taskId: String): Int? =
    taskEntries.indexOfFirst { it.task.id == taskId }
      .takeIf { it >= 0 }

  private fun buildSnapshotLocked(): SessionQueueSnapshot = SessionQueueSnapshot(
    sessionId = sessionId,
    agentId = agentId,
    lifecycleState = lifecycleState,
    nextEnqueueOrder = nextEnqueueOrder,
    tasks = taskEntries.sortedBy { it.enqueueOrder },
    updatedAtEpochMs = clock.nowEpochMs(),
  )

  private fun persistSnapshotLocked() {
    snapshotStore.save(buildSnapshotLocked())
  }

  private fun mapLifecycleToAgentTaskState(state: QueueTaskLifecycleState): AgentTaskState = when (state) {
    QueueTaskLifecycleState.QUEUED,
    QueueTaskLifecycleState.RETRY_PENDING,
    -> AgentTaskState.QUEUED

    QueueTaskLifecycleState.RUNNING,
    QueueTaskLifecycleState.CANCEL_REQUESTED,
    -> AgentTaskState.RUNNING

    QueueTaskLifecycleState.SUSPENDED -> AgentTaskState.SUSPENDED

    QueueTaskLifecycleState.COMPLETED -> AgentTaskState.COMPLETED
    QueueTaskLifecycleState.FAILED -> AgentTaskState.FAILED
    QueueTaskLifecycleState.CANCELLED -> AgentTaskState.CANCELLED
  }

  private companion object {
    val ALLOWED_TASK_TRANSITIONS: Map<QueueTaskLifecycleState, Set<QueueTaskLifecycleState>> = mapOf(
      QueueTaskLifecycleState.QUEUED to setOf(
        QueueTaskLifecycleState.RUNNING,
        QueueTaskLifecycleState.CANCELLED,
      ),
      QueueTaskLifecycleState.RUNNING to setOf(
        QueueTaskLifecycleState.RETRY_PENDING,
        QueueTaskLifecycleState.SUSPENDED,
        QueueTaskLifecycleState.CANCEL_REQUESTED,
        QueueTaskLifecycleState.COMPLETED,
        QueueTaskLifecycleState.FAILED,
        QueueTaskLifecycleState.CANCELLED,
      ),
      QueueTaskLifecycleState.RETRY_PENDING to setOf(
        QueueTaskLifecycleState.QUEUED,
        QueueTaskLifecycleState.CANCELLED,
      ),
      QueueTaskLifecycleState.SUSPENDED to setOf(
        QueueTaskLifecycleState.QUEUED,
        QueueTaskLifecycleState.CANCELLED,
      ),
      QueueTaskLifecycleState.CANCEL_REQUESTED to setOf(
        QueueTaskLifecycleState.CANCELLED,
        QueueTaskLifecycleState.FAILED,
        QueueTaskLifecycleState.COMPLETED,
      ),
      QueueTaskLifecycleState.COMPLETED to emptySet(),
      QueueTaskLifecycleState.FAILED to setOf(
        QueueTaskLifecycleState.RETRY_PENDING,
      ),
      QueueTaskLifecycleState.CANCELLED to emptySet(),
    )

    val ALLOWED_SESSION_TRANSITIONS: Map<SessionLifecycleState, Set<SessionLifecycleState>> = mapOf(
      SessionLifecycleState.IDLE to setOf(SessionLifecycleState.RUNNING, SessionLifecycleState.STOPPED),
      SessionLifecycleState.RUNNING to setOf(SessionLifecycleState.IDLE, SessionLifecycleState.STOPPED),
      SessionLifecycleState.STOPPED to setOf(SessionLifecycleState.IDLE),
    )
  }
}
