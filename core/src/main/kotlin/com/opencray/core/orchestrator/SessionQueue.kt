package com.opencray.core.orchestrator

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ContractSchemaVersion
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
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
  val lastErrorCode: String? = null,
  val lastErrorMessage: String? = null,
) {
  init {
    require(enqueueOrder >= 0) { "SessionQueueTaskSnapshot enqueueOrder must be >= 0." }
    require(attempt >= 0) { "SessionQueueTaskSnapshot attempt must be >= 0." }
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

data class RuntimeExecutionHooks(
  val isCancellationRequested: () -> Boolean,
  val requestRetry: (RetryRequest) -> Unit,
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

class SessionQueue(
  private val sessionId: String,
  private val agentId: String,
  private val runtime: SessionTaskRuntime,
  private val snapshotStore: SessionQueueSnapshotStore,
  private val clock: QueueClock = SystemQueueClock,
  private val config: SessionQueueConfig = SessionQueueConfig(),
) {
  private val taskEntries = mutableListOf<SessionQueueTaskSnapshot>()
  private var lifecycleState: SessionLifecycleState = SessionLifecycleState.IDLE
  private var nextEnqueueOrder: Long = 1L

  init {
    require(sessionId.isNotBlank()) { "SessionQueue sessionId must not be blank." }
    require(agentId.isNotBlank()) { "SessionQueue agentId must not be blank." }

    restore(snapshotStore.load())
    persistSnapshot()
  }

  fun enqueue(task: AgentTask): AgentTask {
    require(task.id.isNotBlank()) { "Queued task id must not be blank." }
    require(taskEntries.none { it.task.id == task.id }) {
      "Task id already exists in session queue: ${task.id}"
    }

    val now = clock.nowEpochMs()
    val queuedTask = task.copy(
      state = AgentTaskState.QUEUED,
      updatedAtEpochMs = maxOf(now, task.createdAtEpochMs),
    )

    taskEntries += SessionQueueTaskSnapshot(
      enqueueOrder = nextEnqueueOrder,
      task = queuedTask,
      lifecycleState = QueueTaskLifecycleState.QUEUED,
      attempt = 0,
    )
    nextEnqueueOrder += 1
    persistSnapshot()
    return queuedTask
  }

  /**
   * Executes up to [maxTasks] runtime calls, always serial and FIFO by enqueue order.
   */
  fun drain(maxTasks: Int = Int.MAX_VALUE): List<ExecutionResult> {
    require(maxTasks >= 0) { "drain maxTasks must be >= 0." }
    if (maxTasks == 0 || lifecycleState == SessionLifecycleState.STOPPED) {
      return emptyList()
    }

    transitionSessionState(SessionLifecycleState.RUNNING)
    val results = mutableListOf<ExecutionResult>()
    var executedCount = 0

    while (executedCount < maxTasks && lifecycleState != SessionLifecycleState.STOPPED) {
      val nextIndex = nextRunnableTaskIndex()
      if (nextIndex == null) break

      val result = executeTaskAt(nextIndex)
      if (result != null) {
        results += result
        executedCount += 1
      }
    }

    if (lifecycleState != SessionLifecycleState.STOPPED) {
      transitionSessionState(SessionLifecycleState.IDLE)
    }

    return results
  }

  /**
   * Cancellation hook exposed to downstream runtime invocations.
   *
   * - QUEUED/RETRY_PENDING tasks are cancelled before execution.
   * - RUNNING tasks transition to CANCEL_REQUESTED and runtime can observe this via hooks.
   */
  fun requestCancel(taskId: String): Boolean {
    val index = indexOfTask(taskId) ?: return false
    val current = taskEntries[index]

    return when (current.lifecycleState) {
      QueueTaskLifecycleState.QUEUED,
      QueueTaskLifecycleState.RETRY_PENDING,
      -> {
        transitionTask(index, QueueTaskLifecycleState.CANCELLED)
        true
      }

      QueueTaskLifecycleState.RUNNING -> {
        transitionTask(index, QueueTaskLifecycleState.CANCEL_REQUESTED)
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
  fun requestRetry(taskId: String): Boolean {
    val index = indexOfTask(taskId) ?: return false
    val current = taskEntries[index]
    if (current.lifecycleState != QueueTaskLifecycleState.FAILED) return false
    if (current.attempt >= config.maxAttempts) return false

    transitionTask(index, QueueTaskLifecycleState.RETRY_PENDING)
    transitionTask(index, QueueTaskLifecycleState.QUEUED)
    return true
  }

  fun stop(): SessionLifecycleState {
    transitionSessionState(SessionLifecycleState.STOPPED)
    return lifecycleState
  }

  fun resume(): SessionLifecycleState {
    if (lifecycleState == SessionLifecycleState.STOPPED) {
      transitionSessionState(SessionLifecycleState.IDLE)
    }
    return lifecycleState
  }

  fun currentSessionState(): SessionLifecycleState = lifecycleState

  fun snapshot(): SessionQueueSnapshot = buildSnapshot()

  private fun restore(snapshot: SessionQueueSnapshot?) {
    if (snapshot == null) return
    if (snapshot.sessionId != sessionId || snapshot.agentId != agentId) return

    lifecycleState =
      if (snapshot.lifecycleState == SessionLifecycleState.STOPPED) {
        SessionLifecycleState.STOPPED
      } else {
        SessionLifecycleState.IDLE
      }

    val restored = snapshot.tasks
      .sortedBy { it.enqueueOrder }
      .map(::normalizeAfterRestart)

    taskEntries.clear()
    taskEntries += restored

    val maxOrder = restored.maxOfOrNull { it.enqueueOrder } ?: 0L
    nextEnqueueOrder = maxOf(snapshot.nextEnqueueOrder, maxOrder + 1)
  }

  private fun normalizeAfterRestart(entry: SessionQueueTaskSnapshot): SessionQueueTaskSnapshot {
    val normalizedLifecycle = when (entry.lifecycleState) {
      QueueTaskLifecycleState.RUNNING,
      QueueTaskLifecycleState.CANCEL_REQUESTED,
      QueueTaskLifecycleState.RETRY_PENDING,
      -> QueueTaskLifecycleState.QUEUED

      else -> entry.lifecycleState
    }

    val mappedTaskState = mapLifecycleToAgentTaskState(normalizedLifecycle)
    val normalizedTask = entry.task.copy(
      state = mappedTaskState,
      updatedAtEpochMs = maxOf(entry.task.updatedAtEpochMs, entry.task.createdAtEpochMs),
    )

    return entry.copy(
      lifecycleState = normalizedLifecycle,
      task = normalizedTask,
    )
  }

  private fun executeTaskAt(index: Int): ExecutionResult? {
    val preRun = taskEntries[index]
    if (preRun.lifecycleState == QueueTaskLifecycleState.RETRY_PENDING) {
      transitionTask(index, QueueTaskLifecycleState.QUEUED)
    }

    val current = taskEntries[index]
    if (current.lifecycleState != QueueTaskLifecycleState.QUEUED) {
      return null
    }

    transitionTask(index, QueueTaskLifecycleState.RUNNING)
    val runningSnapshot = taskEntries[index]

    var retryRequest: RetryRequest? = null
    val hooks = RuntimeExecutionHooks(
      isCancellationRequested = {
        taskEntries.getOrNull(index)?.lifecycleState == QueueTaskLifecycleState.CANCEL_REQUESTED
      },
      requestRetry = { request -> retryRequest = request },
    )

    val normalizedResult = executeRuntimeSafely(runningSnapshot.task, hooks)
    val latest = taskEntries[index]

    val shouldRetry =
      retryRequest != null &&
        normalizedResult.status != ExecutionStatus.SUCCESS &&
        normalizedResult.status != ExecutionStatus.CANCELLED &&
        latest.attempt < config.maxAttempts

    if (shouldRetry) {
      val request = retryRequest!!
      transitionTask(
        index = index,
        to = QueueTaskLifecycleState.RETRY_PENDING,
        errorCode = request.reasonCode,
        errorMessage = request.detail ?: normalizedResult.errorMessage,
      )
      transitionTask(index, QueueTaskLifecycleState.QUEUED)
      return normalizedResult
    }

    when {
      latest.lifecycleState == QueueTaskLifecycleState.CANCEL_REQUESTED ||
        normalizedResult.status == ExecutionStatus.CANCELLED -> {
        transitionTask(
          index = index,
          to = QueueTaskLifecycleState.CANCELLED,
          errorCode = normalizedResult.errorCode,
          errorMessage = normalizedResult.errorMessage,
        )
      }

      normalizedResult.status == ExecutionStatus.SUCCESS -> {
        transitionTask(index, QueueTaskLifecycleState.COMPLETED)
      }

      else -> {
        transitionTask(
          index = index,
          to = QueueTaskLifecycleState.FAILED,
          errorCode = normalizedResult.errorCode,
          errorMessage = normalizedResult.errorMessage,
        )
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

  private fun transitionTask(
    index: Int,
    to: QueueTaskLifecycleState,
    errorCode: String? = null,
    errorMessage: String? = null,
  ) {
    val current = taskEntries[index]
    val allowed = ALLOWED_TASK_TRANSITIONS.getValue(current.lifecycleState)
    require(to == current.lifecycleState || to in allowed) {
      "Invalid task transition for ${current.task.id}: ${current.lifecycleState} -> $to"
    }

    val now = clock.nowEpochMs()
    val nextAttempt = if (to == QueueTaskLifecycleState.RUNNING) current.attempt + 1 else current.attempt
    val nextTask = current.task.copy(
      state = mapLifecycleToAgentTaskState(to),
      updatedAtEpochMs = maxOf(now, current.task.createdAtEpochMs),
    )

    taskEntries[index] = current.copy(
      lifecycleState = to,
      task = nextTask,
      attempt = nextAttempt,
      lastErrorCode = errorCode ?: current.lastErrorCode,
      lastErrorMessage = errorMessage ?: current.lastErrorMessage,
    )
    persistSnapshot()
  }

  private fun transitionSessionState(to: SessionLifecycleState) {
    if (lifecycleState == to) return
    require(to in ALLOWED_SESSION_TRANSITIONS.getValue(lifecycleState)) {
      "Invalid session transition: $lifecycleState -> $to"
    }

    lifecycleState = to
    persistSnapshot()
  }

  private fun nextRunnableTaskIndex(): Int? {
    val sorted = taskEntries.indices.sortedBy { taskEntries[it].enqueueOrder }
    for (index in sorted) {
      val state = taskEntries[index].lifecycleState
      if (state == QueueTaskLifecycleState.QUEUED || state == QueueTaskLifecycleState.RETRY_PENDING) {
        return index
      }
    }
    return null
  }

  private fun indexOfTask(taskId: String): Int? =
    taskEntries.indexOfFirst { it.task.id == taskId }
      .takeIf { it >= 0 }

  private fun buildSnapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
    sessionId = sessionId,
    agentId = agentId,
    lifecycleState = lifecycleState,
    nextEnqueueOrder = nextEnqueueOrder,
    tasks = taskEntries.sortedBy { it.enqueueOrder },
    updatedAtEpochMs = clock.nowEpochMs(),
  )

  private fun persistSnapshot() {
    snapshotStore.save(buildSnapshot())
  }

  private fun mapLifecycleToAgentTaskState(state: QueueTaskLifecycleState): AgentTaskState = when (state) {
    QueueTaskLifecycleState.QUEUED,
    QueueTaskLifecycleState.RETRY_PENDING,
    -> AgentTaskState.QUEUED

    QueueTaskLifecycleState.RUNNING,
    QueueTaskLifecycleState.CANCEL_REQUESTED,
    -> AgentTaskState.RUNNING

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
        QueueTaskLifecycleState.CANCEL_REQUESTED,
        QueueTaskLifecycleState.COMPLETED,
        QueueTaskLifecycleState.FAILED,
        QueueTaskLifecycleState.CANCELLED,
      ),
      QueueTaskLifecycleState.RETRY_PENDING to setOf(
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
