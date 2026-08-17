package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.EXECUTION_KIND_APPROVAL_RESUME
import com.opencray.core.orchestrator.EXECUTION_KIND_CHECKPOINT_RESUME
import com.opencray.core.orchestrator.EXECUTION_KIND_INITIAL
import com.opencray.core.orchestrator.METADATA_EXECUTION_KIND
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.subagent.SubAgentExecutionKey
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

internal data class SessionSubAgentActorTaskDriverCallbacks(
  val recordSubmission: (AgentRunSubmission, AgentTask) -> Unit,
  val runStateByRunId: (String) -> SessionSubAgentRecoveryRunState?,
  val replaceLastResult: (String, ExecutionResult?) -> Unit,
  val notifyTaskStarted: (AgentTask) -> Unit,
  val finalizeTaskResult: (AgentTask, ExecutionResult) -> ExecutionResult,
  val prepareExecutionTask: (AgentTask, String) -> AgentTask,
  val interruptedResultForTask: (AgentTask) -> ExecutionResult,
  val isAwaitingManualResume: (ExecutionResult) -> Boolean,
)

internal class SessionSubAgentActorTaskDriver(
  private val sessionId: String,
  private val runtimeFactory: AgentSessionTaskRuntimeFactory,
  private val executor: ExecutorService,
  private val runtimeLifecycle: HostRuntimeLifecycleDescriptor,
  private val runtimeEventSink: OpenCrayAgentRuntimeEventSink,
  private val callbacks: SessionSubAgentActorTaskDriverCallbacks,
) {
  private val lock = Any()
  private val lanesByHandleKey = linkedMapOf<SubAgentExecutionKey, ActorLane>()
  private val handleKeysByTaskId = linkedMapOf<String, SubAgentExecutionKey>()

  fun listTasks(): List<AgentTask> = synchronized(lock) {
    lanesByHandleKey.values.map(ActorLane::taskSnapshotLocked)
  }

  fun submitActorTask(
    agentId: String,
    parentRunId: String,
    createdAtEpochMs: Long,
    submissionSource: String,
  ): Boolean {
    val handleKey = SubAgentExecutionKey(
      parentRunId = parentRunId,
      agentId = agentId,
    )
    val taskId = syntheticSubAgentActorTaskId(
      sessionId = sessionId,
      agentId = agentId,
      parentRunId = parentRunId,
    )
    val runId = syntheticSubAgentActorRunId(taskId)
    val persistedRunState = callbacks.runStateByRunId(runId)
    val awaitingManualResume = persistedRunState?.lastResult?.let(callbacks.isAwaitingManualResume) == true
    val taskAcceptedAtEpochMs = persistedRunState?.submission?.acceptedAtEpochMs
      ?: maxOf(System.currentTimeMillis(), createdAtEpochMs)
    val baseTask = syntheticSubAgentActorTask(
      sessionId = sessionId,
      agentId = agentId,
      parentRunId = parentRunId,
      createdAtEpochMs = taskAcceptedAtEpochMs,
      metadata = runtimeLifecycle.taskMetadata(
        submissionSource = submissionSource,
      ),
    )
    val initialTask = baseTask.copy(
      state = if (awaitingManualResume) {
        AgentTaskState.SUSPENDED
      } else {
        AgentTaskState.QUEUED
      },
      updatedAtEpochMs = maxOf(
        baseTask.createdAtEpochMs,
        persistedRunState?.lastResult?.finishedAtEpochMs ?: 0L,
      ),
    )
    val submission = persistedRunState?.submission ?: AgentRunSubmission(
      sessionId = sessionId,
      runId = syntheticSubAgentActorRunId(initialTask.id),
      taskId = initialTask.id,
      acceptedAtEpochMs = taskAcceptedAtEpochMs,
      lifecycleDiagnostics = runLifecycleDiagnosticsFrom(initialTask.metadata),
    )
    var createdLane = false
    val lane = synchronized(lock) {
      lanesByHandleKey[handleKey] ?: run {
        if (persistedRunState == null) {
          callbacks.recordSubmission(submission, initialTask)
        }
        ActorLane(
          handleKey = handleKey,
          submission = submission,
          initialTask = initialTask,
        ).also { created ->
          lanesByHandleKey[handleKey] = created
          handleKeysByTaskId[submission.taskId] = handleKey
          createdLane = true
        }
      }
    }
    if (!createdLane) {
      return lane.wakeUpByResubmission()
    }
    if (awaitingManualResume) {
      return false
    }
    lane.launchExecution(
      executionKind = initialTask.metadata[METADATA_EXECUTION_KIND]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: EXECUTION_KIND_INITIAL,
      clearPreviousResult = persistedRunState?.lastResult != null,
    )
    return true
  }

  fun requestCancel(taskId: String): Boolean =
    laneForTaskId(taskId)?.requestCancel() ?: false

  fun requestCancel(
    agentId: String,
    parentRunId: String,
  ): Boolean = laneForHandleKey(
    SubAgentExecutionKey(
      parentRunId = parentRunId,
      agentId = agentId,
    ),
  )?.requestCancel() ?: false

  fun requestResume(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String>,
  ): Boolean = laneForTaskId(taskId)?.requestResume(
    executionKind = executionKind,
    taskMetadataUpdates = taskMetadataUpdates,
  ) ?: false

  fun requestResume(
    agentId: String,
    parentRunId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String>,
  ): Boolean = laneForHandleKey(
    SubAgentExecutionKey(
      parentRunId = parentRunId,
      agentId = agentId,
    ),
  )?.requestResume(
    executionKind = executionKind,
    taskMetadataUpdates = taskMetadataUpdates,
  ) ?: false

  private fun laneForTaskId(taskId: String): ActorLane? = synchronized(lock) {
    handleKeysByTaskId[taskId]?.let(lanesByHandleKey::get)
  }

  private fun laneForHandleKey(handleKey: SubAgentExecutionKey): ActorLane? = synchronized(lock) {
    lanesByHandleKey[handleKey]
  }

  private fun removeLaneLocked(lane: ActorLane) {
    if (lanesByHandleKey[lane.handleKey] !== lane) {
      return
    }
    lanesByHandleKey.remove(lane.handleKey)
    handleKeysByTaskId.remove(lane.submission.taskId)
  }

  private fun cancelledResultForTask(
    task: AgentTask,
    errorMessage: String,
  ): ExecutionResult = ExecutionResult(
    taskId = task.id,
    status = ExecutionStatus.CANCELLED,
    errorCode = "SUBAGENT_ACTOR_CANCELLED",
    errorMessage = errorMessage,
    startedAtEpochMs = task.createdAtEpochMs,
    finishedAtEpochMs = System.currentTimeMillis(),
    metadata = task.metadata,
  )

  private fun AgentTask.withState(state: AgentTaskState): AgentTask = copy(
    state = state,
    updatedAtEpochMs = maxOf(System.currentTimeMillis(), createdAtEpochMs, updatedAtEpochMs),
  )

  private inner class ActorLane(
    val handleKey: SubAgentExecutionKey,
    val submission: AgentRunSubmission,
    initialTask: AgentTask,
  ) {
    private var task: AgentTask = initialTask
    private var cancelRequested: AtomicBoolean = AtomicBoolean(false)
    private var future: FutureTask<Unit>? = null
    private var completionToken: AtomicBoolean? = null

    fun taskSnapshotLocked(): AgentTask = task

    fun wakeUpByResubmission(): Boolean {
      val launchSpec = synchronized(lock) {
        if (lanesByHandleKey[handleKey] !== this || future != null) {
          return@synchronized null
        }
        when (task.state) {
          AgentTaskState.QUEUED -> {
            val runState = callbacks.runStateByRunId(submission.runId)
            if (runState?.lastResult == null) {
              ActorLaneLaunchSpec(
                executionKind = task.metadata[METADATA_EXECUTION_KIND]
                  ?.trim()
                  ?.takeIf(String::isNotBlank)
                  ?: EXECUTION_KIND_INITIAL,
                clearPreviousResult = false,
              )
            } else if (callbacks.isAwaitingManualResume(runState.lastResult)) {
              null
            } else {
              ActorLaneLaunchSpec(
                executionKind = EXECUTION_KIND_INITIAL,
                clearPreviousResult = true,
              )
            }
          }

          AgentTaskState.SUSPENDED -> null
          else -> null
        }
      }
      if (launchSpec == null) {
        return false
      }
      launchExecution(
        executionKind = launchSpec.executionKind,
        clearPreviousResult = launchSpec.clearPreviousResult,
      )
      return true
    }

    fun requestCancel(): Boolean {
      val actorSpec = synchronized(lock) {
        syntheticSubAgentTaskSpec(task) as? SyntheticSubAgentActorTaskSpec
      }
      actorSpec?.let { spec ->
        runtimeFactory.cancelActiveSubAgentExecution(
          sessionId = sessionId,
          agentId = spec.agentId,
          parentRunId = spec.parentRunId,
        )
      }
      var activeCancellation: ActiveCancellation? = null
      var queuedCancellation: QueuedCancellation? = null
      var pausedCancellation: PausedCancellation? = null
      synchronized(lock) {
        if (lanesByHandleKey[handleKey] !== this) {
          return@synchronized
        }
        val activeFuture = future
        if (activeFuture != null) {
          cancelRequested.set(true)
          completionToken?.set(false)
          val cancelledTask = task.withState(AgentTaskState.CANCELLED)
          task = cancelledTask
          future = null
          completionToken = null
          activeCancellation = ActiveCancellation(
            future = activeFuture,
            task = cancelledTask,
          )
          removeLaneLocked(this)
          return@synchronized
        }
        val runState = callbacks.runStateByRunId(submission.runId)
        if (task.state == AgentTaskState.QUEUED && runState?.lastResult == null) {
          val cancelledTask = task.withState(AgentTaskState.CANCELLED)
          task = cancelledTask
          queuedCancellation = QueuedCancellation(
            runId = submission.runId,
            task = cancelledTask,
          )
          removeLaneLocked(this)
          return@synchronized
        }
        val lastResult = runState?.lastResult ?: return@synchronized
        if (!callbacks.isAwaitingManualResume(lastResult)) {
          return@synchronized
        }
        task = task.withState(AgentTaskState.CANCELLED)
        pausedCancellation = PausedCancellation(
          runId = submission.runId,
          result = ExecutionResult(
            taskId = submission.taskId,
            status = ExecutionStatus.CANCELLED,
            errorCode = "SUBAGENT_ACTOR_CANCELLED",
            errorMessage = "Subagent actor execution was cancelled before it resumed.",
            startedAtEpochMs = lastResult.startedAtEpochMs,
            finishedAtEpochMs = System.currentTimeMillis(),
            metadata = lastResult.metadata,
          ),
        )
        removeLaneLocked(this)
      }
      activeCancellation?.let { cancellation ->
        cancellation.future.cancel(true)
        callbacks.finalizeTaskResult(
          cancellation.task,
          cancelledResultForTask(
            task = cancellation.task,
            errorMessage = "Subagent actor execution was cancelled.",
          ),
        )
        return true
      }
      queuedCancellation?.let { cancellation ->
        callbacks.replaceLastResult(
          cancellation.runId,
          cancelledResultForTask(
            task = cancellation.task,
            errorMessage = "Subagent actor execution was cancelled before it restarted.",
          ),
        )
        return true
      }
      pausedCancellation?.let { cancellation ->
        callbacks.replaceLastResult(cancellation.runId, cancellation.result)
        return true
      }
      return false
    }

    fun requestResume(
      executionKind: String,
      taskMetadataUpdates: Map<String, String>,
    ): Boolean {
      require(
        executionKind == EXECUTION_KIND_APPROVAL_RESUME ||
          executionKind == EXECUTION_KIND_CHECKPOINT_RESUME,
      ) {
        "Unsupported subagent actor resume execution kind: $executionKind"
      }
      var shouldLaunch = false
      synchronized(lock) {
        if (lanesByHandleKey[handleKey] !== this || future != null) {
          return@synchronized
        }
        val runState = callbacks.runStateByRunId(submission.runId) ?: return@synchronized
        val lastResult = runState.lastResult ?: return@synchronized
        if (!callbacks.isAwaitingManualResume(lastResult)) {
          return@synchronized
        }
        task = task.copy(metadata = task.metadata + taskMetadataUpdates)
        shouldLaunch = true
      }
      if (!shouldLaunch) {
        return false
      }
      launchExecution(
        executionKind = executionKind,
        clearPreviousResult = true,
      )
      return true
    }

    fun launchExecution(
      executionKind: String,
      clearPreviousResult: Boolean,
    ) {
      val baseTask = synchronized(lock) { task }
      val executionTask = callbacks.prepareExecutionTask(baseTask, executionKind)
      val queuedTask = executionTask.withState(AgentTaskState.QUEUED)
      val actorSpec = syntheticSubAgentTaskSpec(executionTask) as? SyntheticSubAgentActorTaskSpec
        ?: error("Subagent actor execution requires an actor task spec.")
      val executionCancelRequested = AtomicBoolean(false)
      val executionCompletionToken = AtomicBoolean(true)
      val futureTask = FutureTask<Unit> {
        val runningTask = markRunning(
          executionTask = executionTask,
          executionCompletionToken = executionCompletionToken,
        )
        if (!executionCompletionToken.get()) {
          return@FutureTask
        }
        callbacks.notifyTaskStarted(runningTask)
        val executionHooks = RuntimeExecutionHooks(
          isCancellationRequested = executionCancelRequested::get,
          requestRetry = { _: RetryRequest -> Unit },
        )
        val result = try {
          runtimeFactory.executeSubAgentActorTask(
            sessionId = sessionId,
            task = runningTask,
            hooks = executionHooks,
            eventSink = runtimeEventSink,
            agentId = actorSpec.agentId,
            parentRunId = actorSpec.parentRunId,
          ) ?: error("Subagent actor execution requires a runtime actor path.")
        } catch (_: InterruptedException) {
          callbacks.interruptedResultForTask(runningTask)
        }
        completeExecution(
          task = runningTask,
          result = result,
          executionCompletionToken = executionCompletionToken,
        )
      }
      var scheduled = false
      synchronized(lock) {
        if (lanesByHandleKey[handleKey] !== this) {
          return@synchronized
        }
        task = queuedTask
        cancelRequested = executionCancelRequested
        completionToken = executionCompletionToken
        future = futureTask
        scheduled = true
      }
      if (!scheduled) {
        return
      }
      if (clearPreviousResult) {
        callbacks.replaceLastResult(submission.runId, null)
      }
      executor.execute(futureTask)
    }

    private fun markRunning(
      executionTask: AgentTask,
      executionCompletionToken: AtomicBoolean,
    ): AgentTask {
      if (!executionCompletionToken.get()) {
        return executionTask
      }
      val runningTask = executionTask.withState(AgentTaskState.RUNNING)
      synchronized(lock) {
        if (
          lanesByHandleKey[handleKey] === this &&
          completionToken === executionCompletionToken &&
          executionCompletionToken.get()
        ) {
          task = runningTask
        }
      }
      return runningTask
    }

    private fun completeExecution(
      task: AgentTask,
      result: ExecutionResult,
      executionCompletionToken: AtomicBoolean,
    ) {
      if (!executionCompletionToken.compareAndSet(true, false)) {
        return
      }
      val finalizedResult = callbacks.finalizeTaskResult(task, result)
      synchronized(lock) {
        if (
          lanesByHandleKey[handleKey] !== this ||
          completionToken !== executionCompletionToken
        ) {
          return@synchronized
        }
        future = null
        completionToken = null
        cancelRequested = AtomicBoolean(false)
        if (callbacks.isAwaitingManualResume(finalizedResult)) {
          this.task = task.withState(AgentTaskState.SUSPENDED)
        } else {
          removeLaneLocked(this)
        }
      }
    }
  }

  private data class ActiveCancellation(
    val future: FutureTask<Unit>,
    val task: AgentTask,
  )

  private data class QueuedCancellation(
    val runId: String,
    val task: AgentTask,
  )

  private data class PausedCancellation(
    val runId: String,
    val result: ExecutionResult,
  )

  private data class ActorLaneLaunchSpec(
    val executionKind: String,
    val clearPreviousResult: Boolean,
  )
}
