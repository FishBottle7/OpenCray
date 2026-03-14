package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.AgentLoop
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.OpenCrayAgentEngine
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import java.util.UUID
import java.util.concurrent.ExecutorService

internal interface AgentSessionRuntimeManager {
  fun forSession(sessionId: String): AgentSessionHandle

  fun observe(listener: AgentSessionRuntimeListener): () -> Unit

  fun release(sessionId: String)

  fun releaseIdleSessions()
}

internal interface AgentSessionHandle {
  val sessionId: String

  fun submitPrompt(
    userText: String,
    pendingMessageId: String,
    visibleThroughMessageId: String,
    policyDecision: PolicyDecision,
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask

  fun ensureProcessing()

  fun requestCancel(taskId: String): Boolean

  fun requestRetry(taskId: String): Boolean

  fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int

  fun resume(): SessionLifecycleState

  fun snapshot(): SessionQueueSnapshot

  fun hasPendingWork(): Boolean
}

internal interface AgentSessionRuntimeListener {
  fun onTaskStarted(sessionId: String, task: AgentTask) = Unit

  fun onTaskFinished(sessionId: String, task: AgentTask, result: ExecutionResult) = Unit

  fun onToolCall(sessionId: String, task: AgentTask, turn: Int, call: AgentToolCall) = Unit

  fun onToolResult(sessionId: String, task: AgentTask, turn: Int, call: AgentToolCall, result: AgentToolResult) = Unit
}

internal interface AgentSessionTaskRuntimeFactory {
  fun create(
    sessionId: String,
    eventSink: OpenCrayAgentRuntimeEventSink,
  ): SessionTaskRuntime
}

internal class DefaultAgentSessionRuntimeManager(
  private val agentId: String,
  private val runtimeFactory: AgentSessionTaskRuntimeFactory,
  private val snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  private val executor: ExecutorService,
) : AgentSessionRuntimeManager {
  private val listeners = linkedSetOf<AgentSessionRuntimeListener>()
  private val sessions = linkedMapOf<String, ManagedAgentSessionHandle>()
  private val lock = Any()

  override fun forSession(sessionId: String): AgentSessionHandle = synchronized(lock) {
    sessions.getOrPut(sessionId) {
      ManagedAgentSessionHandle(
        sessionId = sessionId,
        agentId = agentId,
        runtimeFactory = runtimeFactory,
        snapshotStoreFactory = snapshotStoreFactory,
        executor = executor,
        listenerProvider = { synchronized(lock) { listeners.toList() } },
      )
    }.also { it.touch() }
  }

  override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = synchronized(lock) {
    listeners += listener
    return {
      synchronized(lock) {
        listeners -= listener
      }
    }
  }

  override fun release(sessionId: String) {
    synchronized(lock) {
      sessions.remove(sessionId)
    }
  }

  override fun releaseIdleSessions() {
    synchronized(lock) {
      val iterator = sessions.iterator()
      while (iterator.hasNext()) {
        val entry = iterator.next()
        if (!entry.value.hasPendingWork()) {
          iterator.remove()
        }
      }
    }
  }
}

private class ManagedAgentSessionHandle(
  override val sessionId: String,
  private val agentId: String,
  runtimeFactory: AgentSessionTaskRuntimeFactory,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  private val executor: ExecutorService,
  private val listenerProvider: () -> List<AgentSessionRuntimeListener>,
) : AgentSessionHandle {
  private val processingLock = Any()
  private var processing: Boolean = false
  private var lastAccessEpochMs: Long = System.currentTimeMillis()
  private val baseRuntime = runtimeFactory.create(
    sessionId = sessionId,
    eventSink = object : OpenCrayAgentRuntimeEventSink {
      override fun onToolCall(task: AgentTask, turn: Int, call: AgentToolCall) {
        listenerProvider().forEach { listener ->
          listener.onToolCall(sessionId = sessionId, task = task, turn = turn, call = call)
        }
      }

      override fun onToolResult(task: AgentTask, turn: Int, call: AgentToolCall, result: AgentToolResult) {
        listenerProvider().forEach { listener ->
          listener.onToolResult(
            sessionId = sessionId,
            task = task,
            turn = turn,
            call = call,
            result = result,
          )
        }
      }
    },
  )
  private val loop = OpenCrayAgentEngine(
    runtime = SessionTaskRuntime { task, hooks ->
      listenerProvider().forEach { listener ->
        listener.onTaskStarted(sessionId = sessionId, task = task)
      }
      val result = baseRuntime.execute(task, hooks)
      listenerProvider().forEach { listener ->
        listener.onTaskFinished(sessionId = sessionId, task = task, result = result)
      }
      result
    },
  ).create(
    sessionId = sessionId,
    agentId = agentId,
    snapshotStore = snapshotStoreFactory.forChatSession(sessionId),
  )

  override fun submitPrompt(
    userText: String,
    pendingMessageId: String,
    visibleThroughMessageId: String,
    policyDecision: PolicyDecision,
    metadata: Map<String, String>,
  ): AgentTask {
    touch()
    val task = AgentTask(
      id = "prompt-$sessionId-${UUID.randomUUID().toString().take(8)}",
      type = com.opencray.core.contracts.AgentTaskType.PROMPT,
      input = userText,
      policyDecision = policyDecision,
      createdAtEpochMs = System.currentTimeMillis(),
      metadata = metadata + mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
        AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to visibleThroughMessageId,
      ),
    )
    return loop.submit(task)
  }

  override fun ensureProcessing() {
    touch()
    val shouldSchedule = synchronized(processingLock) {
      if (processing) {
        false
      } else {
        processing = true
        true
      }
    }
    if (!shouldSchedule) {
      return
    }
    executor.execute {
      try {
        loop.resume()
        while (true) {
          if (!hasPendingWork()) {
            break
          }
          val results = loop.runUntilIdle()
          if (results.isEmpty()) {
            break
          }
        }
      } finally {
        val reschedule = synchronized(processingLock) {
          processing = false
          hasPendingWork()
        }
        if (reschedule) {
          ensureProcessing()
        }
      }
    }
  }

  override fun requestCancel(taskId: String): Boolean {
    touch()
    return loop.requestCancel(taskId)
  }

  override fun requestRetry(taskId: String): Boolean {
    touch()
    val retried = loop.requestRetry(taskId)
    if (retried) {
      ensureProcessing()
    }
    return retried
  }

  override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int {
    if (pendingMessageIds.isEmpty()) {
      return 0
    }
    val candidateTaskIds = snapshot().tasks
      .filter { taskSnapshot ->
        taskSnapshot.lifecycleState != com.opencray.core.orchestrator.QueueTaskLifecycleState.COMPLETED &&
          taskSnapshot.lifecycleState != com.opencray.core.orchestrator.QueueTaskLifecycleState.CANCELLED &&
          taskSnapshot.lifecycleState != com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED &&
          taskSnapshot.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] in pendingMessageIds
      }
      .map { taskSnapshot -> taskSnapshot.task.id }

    var cancelled = 0
    candidateTaskIds.forEach { taskId ->
      if (requestCancel(taskId)) {
        cancelled += 1
      }
    }
    return cancelled
  }

  override fun resume(): SessionLifecycleState {
    touch()
    val state = loop.resume()
    if (hasPendingWork()) {
      ensureProcessing()
    }
    return state
  }

  override fun snapshot(): SessionQueueSnapshot {
    touch()
    return loop.snapshot()
  }

  override fun hasPendingWork(): Boolean = loop.snapshot().tasks.any { taskSnapshot ->
    when (taskSnapshot.task.state) {
      AgentTaskState.QUEUED,
      AgentTaskState.RUNNING,
      -> true

      AgentTaskState.COMPLETED,
      AgentTaskState.CANCELLED,
      AgentTaskState.FAILED,
      -> false
    }
  }

  fun touch() {
    lastAccessEpochMs = System.currentTimeMillis()
  }
}
