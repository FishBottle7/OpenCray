package com.opencray.core.orchestrator

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult

/**
 * Single-session orchestration facade.
 *
 * V1 scope intentionally remains serial and FIFO via [SessionQueue].
 */
class AgentLoop(
  private val queue: SessionQueue,
) {
  fun submit(task: AgentTask): AgentTask = queue.enqueue(task)

  fun runUntilIdle(maxTasks: Int = Int.MAX_VALUE): List<ExecutionResult> = queue.drain(maxTasks)

  fun requestCancel(taskId: String): Boolean = queue.requestCancel(taskId)

  fun requestRetry(
    taskId: String,
    taskMetadataUpdates: Map<String, String> = emptyMap(),
  ): Boolean = queue.requestRetry(
    taskId = taskId,
    taskMetadataUpdates = taskMetadataUpdates,
  )

  fun reconcileFailure(
    taskId: String,
    errorCode: String,
    errorMessage: String,
  ): Boolean = queue.reconcileFailure(
    taskId = taskId,
    errorCode = errorCode,
    errorMessage = errorMessage,
  )

  fun requestResumeTask(taskId: String): Boolean = queue.requestResumeTask(taskId)

  fun requestResumeTask(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String> = emptyMap(),
  ): Boolean = queue.requestResumeTask(
    taskId = taskId,
    executionKind = executionKind,
    taskMetadataUpdates = taskMetadataUpdates,
  )

  fun stop(): SessionLifecycleState = queue.stop()

  fun resume(): SessionLifecycleState = queue.resume()

  fun state(): SessionLifecycleState = queue.currentSessionState()

  fun snapshot(): SessionQueueSnapshot = queue.snapshot()
}
