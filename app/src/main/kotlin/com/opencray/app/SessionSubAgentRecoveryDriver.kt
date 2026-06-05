package com.opencray.app

import com.opencray.core.contracts.AgentTask
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

internal data class SessionSubAgentRecoveryRunState(
  val submission: AgentRunSubmission,
  val lastResult: ExecutionResult?,
)

internal data class SessionSubAgentRecoveryDriverCallbacks(
  val recordSubmission: (AgentRunSubmission, AgentTask) -> Unit,
  val runStateByTaskId: (String) -> SessionSubAgentRecoveryRunState?,
  val runStateByRunId: (String) -> SessionSubAgentRecoveryRunState?,
  val replaceLastResult: (String, ExecutionResult?) -> Unit,
  val replaceDetachedTask: (String, AgentTask?) -> Unit,
  val notifyTaskStarted: (AgentTask) -> Unit,
  val finalizeTaskResult: (AgentTask, ExecutionResult) -> ExecutionResult,
  val prepareExecutionTask: (AgentTask, String) -> AgentTask,
  val interruptedResultForTask: (AgentTask) -> ExecutionResult,
  val isAwaitingManualResume: (ExecutionResult) -> Boolean,
)

internal class SessionSubAgentRecoveryDriver(
  private val sessionId: String,
  private val runtimeFactory: AgentSessionTaskRuntimeFactory,
  private val executor: ExecutorService,
  private val runtimeLifecycle: HostRuntimeLifecycleDescriptor,
  private val runtimeEventSink: OpenCrayAgentRuntimeEventSink,
  private val callbacks: SessionSubAgentRecoveryDriverCallbacks,
) {
  private val lock = Any()
  private val tasksByTaskId = linkedMapOf<String, RecoveryTaskState>()
  private val taskIdsByHandleKey = linkedMapOf<SubAgentExecutionKey, String>()

  fun submit(
    agentId: String,
    parentRunId: String,
    taskId: String,
    createdAtEpochMs: Long,
    submissionSource: String,
  ): AgentRunSubmission {
    val handleKey = SubAgentExecutionKey(
      parentRunId = parentRunId,
      agentId = agentId,
    )
    synchronized(lock) {
      val existingTaskId = taskIdsByHandleKey[handleKey]
      if (existingTaskId != null) {
        val existingState = tasksByTaskId[existingTaskId]
        if (existingState != null) {
          return existingState.submission
        }
        taskIdsByHandleKey.remove(handleKey)
      }
    }
    val task = detachedSubAgentRecoveryWaitTask(
      sessionId = sessionId,
      agentId = agentId,
      parentRunId = parentRunId,
      taskId = taskId,
      createdAtEpochMs = createdAtEpochMs,
      metadata = runtimeLifecycle.taskMetadata(
        submissionSource = submissionSource,
      ),
    )
    val submission = AgentRunSubmission(
      sessionId = sessionId,
      runId = detachedSubAgentRecoveryRunId(taskId),
      taskId = taskId,
      acceptedAtEpochMs = maxOf(System.currentTimeMillis(), createdAtEpochMs),
      lifecycleDiagnostics = runLifecycleDiagnosticsFrom(task.metadata),
    )
    callbacks.recordSubmission(submission, task)
    launchExecution(
      handleKey = handleKey,
      submission = submission,
      task = task,
      executionKind = task.metadata[METADATA_EXECUTION_KIND]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: EXECUTION_KIND_INITIAL,
      clearPreviousResult = false,
    )
    return submission
  }

  fun requestCancel(taskId: String): Boolean {
    val state = synchronized(lock) {
      tasksByTaskId[taskId]
    } ?: return false
    (detachedControlTaskSpec(state.task) as? DetachedSubAgentRecoveryWaitTaskSpec)?.let { spec ->
      runtimeFactory.cancelActiveSubAgentExecution(
        sessionId = sessionId,
        agentId = spec.agentId,
        parentRunId = spec.parentRunId,
      )
    }
    state.cancelRequested.set(true)
    val future = synchronized(lock) {
      tasksByTaskId[taskId]?.future
    }
    if (future != null) {
      future.cancel(true)
      return true
    }
    val runState = callbacks.runStateByTaskId(taskId) ?: return false
    val lastResult = runState.lastResult ?: return false
    if (!callbacks.isAwaitingManualResume(lastResult)) {
      return false
    }
    val cancelledResult = ExecutionResult(
      taskId = taskId,
      status = ExecutionStatus.CANCELLED,
      errorCode = "SUBAGENT_RECOVERY_CANCELLED",
      errorMessage = "Subagent recovery execution was cancelled before it resumed.",
      startedAtEpochMs = lastResult.startedAtEpochMs,
      finishedAtEpochMs = System.currentTimeMillis(),
      metadata = lastResult.metadata,
    )
    callbacks.replaceLastResult(runState.submission.runId, cancelledResult)
    callbacks.replaceDetachedTask(runState.submission.runId, null)
    synchronized(lock) {
      if (tasksByTaskId[taskId]?.handleKey == state.handleKey) {
        taskIdsByHandleKey.remove(state.handleKey)
      }
      tasksByTaskId.remove(taskId)
    }
    return true
  }

  fun requestResume(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String>,
  ): Boolean {
    require(
      executionKind == EXECUTION_KIND_APPROVAL_RESUME ||
        executionKind == EXECUTION_KIND_CHECKPOINT_RESUME,
    ) {
      "Unsupported subagent recovery resume execution kind: $executionKind"
    }
    val state = synchronized(lock) {
      tasksByTaskId[taskId]
    } ?: return false
    if (state.future != null) {
      return false
    }
    val runState = callbacks.runStateByRunId(state.submission.runId) ?: return false
    val lastResult = runState.lastResult ?: return false
    if (!callbacks.isAwaitingManualResume(lastResult)) {
      return false
    }
    val resumedTask = state.task.copy(
      metadata = state.task.metadata + taskMetadataUpdates,
    )
    launchExecution(
      handleKey = state.handleKey,
      submission = state.submission,
      task = resumedTask,
      executionKind = executionKind,
      clearPreviousResult = true,
    )
    return true
  }

  fun listTasks(): List<AgentTask> = synchronized(lock) {
    tasksByTaskId.values.map(RecoveryTaskState::task)
  }

  fun restorePendingTask(
    submission: AgentRunSubmission,
    task: AgentTask,
    lastResult: ExecutionResult,
  ): Boolean {
    if (!callbacks.isAwaitingManualResume(lastResult)) {
      return false
    }
    val recoverySpec = detachedControlTaskSpec(task) as? DetachedSubAgentRecoveryWaitTaskSpec
      ?: return false
    val handleKey = SubAgentExecutionKey(
      parentRunId = recoverySpec.parentRunId,
      agentId = recoverySpec.agentId,
    )
    synchronized(lock) {
      taskIdsByHandleKey[handleKey] = submission.taskId
      tasksByTaskId[submission.taskId] = RecoveryTaskState(
        handleKey = handleKey,
        submission = submission,
        task = task,
        cancelRequested = AtomicBoolean(false),
        future = null,
      )
    }
    callbacks.replaceDetachedTask(submission.runId, task)
    return true
  }

  fun restoreInFlightTask(
    submission: AgentRunSubmission,
    task: AgentTask,
  ): Boolean {
    val recoverySpec = detachedControlTaskSpec(task) as? DetachedSubAgentRecoveryWaitTaskSpec
      ?: return false
    val handleKey = SubAgentExecutionKey(
      parentRunId = recoverySpec.parentRunId,
      agentId = recoverySpec.agentId,
    )
    launchExecution(
      handleKey = handleKey,
      submission = submission,
      task = task,
      executionKind = task.metadata[METADATA_EXECUTION_KIND]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: EXECUTION_KIND_INITIAL,
      clearPreviousResult = false,
    )
    return true
  }

  private fun launchExecution(
    handleKey: SubAgentExecutionKey,
    submission: AgentRunSubmission,
    task: AgentTask,
    executionKind: String,
    clearPreviousResult: Boolean,
  ) {
    val executionTask = callbacks.prepareExecutionTask(task, executionKind)
    callbacks.replaceDetachedTask(submission.runId, executionTask)
    val recoverySpec = detachedControlTaskSpec(executionTask) as? DetachedSubAgentRecoveryWaitTaskSpec
      ?: error("Subagent recovery execution requires a recovery task spec.")
    val cancelRequested = AtomicBoolean(false)
    val future = FutureTask<Unit> {
      callbacks.notifyTaskStarted(executionTask)
      val executionHooks = RuntimeExecutionHooks(
        isCancellationRequested = cancelRequested::get,
        requestRetry = { _: RetryRequest -> Unit },
      )
      val result = try {
        runtimeFactory.executeDetachedSubAgentRecoveryTask(
          sessionId = sessionId,
          task = executionTask,
          hooks = executionHooks,
          eventSink = runtimeEventSink,
          agentId = recoverySpec.agentId,
          parentRunId = recoverySpec.parentRunId,
        ) ?: error("Subagent recovery execution requires a runtime recovery path.")
      } catch (_: InterruptedException) {
        callbacks.interruptedResultForTask(executionTask)
      }
      completeExecution(
        submission = submission,
        task = executionTask,
        result = result,
      )
    }
    synchronized(lock) {
      taskIdsByHandleKey[handleKey] = submission.taskId
      tasksByTaskId[submission.taskId] = RecoveryTaskState(
        handleKey = handleKey,
        submission = submission,
        task = executionTask,
        cancelRequested = cancelRequested,
        future = future,
      )
    }
    if (clearPreviousResult) {
      callbacks.replaceLastResult(submission.runId, null)
    }
    executor.execute(future)
  }

  private fun completeExecution(
    submission: AgentRunSubmission,
    task: AgentTask,
    result: ExecutionResult,
  ) {
    val finalizedResult = callbacks.finalizeTaskResult(task, result)
    synchronized(lock) {
      val latest = tasksByTaskId[submission.taskId] ?: return
      tasksByTaskId[submission.taskId] = latest.copy(future = null)
      if (!callbacks.isAwaitingManualResume(finalizedResult)) {
        callbacks.replaceDetachedTask(submission.runId, null)
        if (taskIdsByHandleKey[latest.handleKey] == submission.taskId) {
          taskIdsByHandleKey.remove(latest.handleKey)
        }
        tasksByTaskId.remove(submission.taskId)
      }
    }
  }

  private data class RecoveryTaskState(
    val handleKey: SubAgentExecutionKey,
    val submission: AgentRunSubmission,
    val task: AgentTask,
    val cancelRequested: AtomicBoolean,
    val future: FutureTask<Unit>?,
  )
}
