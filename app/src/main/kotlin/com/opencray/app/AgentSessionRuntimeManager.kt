package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.AgentLoop
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.OpenCrayAgentEngine
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import java.util.UUID
import java.util.concurrent.ExecutorService

internal data class AgentRunSubmission(
  val sessionId: String,
  val runId: String,
  val taskId: String,
  val acceptedAtEpochMs: Long,
)

internal data class AgentRunSnapshot(
  val sessionId: String,
  val runId: String,
  val taskId: String,
  val acceptedAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val lifecycleState: QueueTaskLifecycleState?,
  val taskState: AgentTaskState?,
  val attempt: Int = 0,
  val executionStatus: ExecutionStatus? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val responseFormat: String? = null,
  val resultMetadata: Map<String, String> = emptyMap(),
  val pendingMessageId: String? = null,
  val lastEvent: OpenCrayAgentRunEvent? = null,
) {
  val isTerminal: Boolean
    get() = when (lifecycleState) {
      QueueTaskLifecycleState.QUEUED,
      QueueTaskLifecycleState.RUNNING,
      QueueTaskLifecycleState.RETRY_PENDING,
      QueueTaskLifecycleState.SUSPENDED,
      QueueTaskLifecycleState.CANCEL_REQUESTED,
      -> false

      QueueTaskLifecycleState.COMPLETED,
      QueueTaskLifecycleState.FAILED,
      QueueTaskLifecycleState.CANCELLED,
      -> true

      null -> executionStatus != null
    }
}

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
  ): AgentRunSubmission

  fun ensureProcessing()

  fun requestCancel(taskId: String): Boolean

  fun requestRetry(taskId: String): Boolean

  fun requestResumeTask(taskId: String): Boolean

  fun listRuns(): List<AgentRunSnapshot>

  fun findRun(runId: String): AgentRunSnapshot?

  fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot?

  fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int

  fun resume(): SessionLifecycleState

  fun snapshot(): SessionQueueSnapshot

  fun hasPendingWork(): Boolean
}

internal interface AgentSessionRuntimeListener {
  fun onTaskStarted(sessionId: String, task: AgentTask) = Unit

  fun onTaskFinished(sessionId: String, task: AgentTask, result: ExecutionResult) = Unit

  fun onRunEvent(sessionId: String, task: AgentTask, event: OpenCrayAgentRunEvent) = Unit

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
  private val runLock = Any()
  private val runRecordsById = linkedMapOf<String, ManagedRunRecord>()
  private val processingLock = Any()
  private var processing: Boolean = false
  private var lastAccessEpochMs: Long = System.currentTimeMillis()
  private val baseRuntime = runtimeFactory.create(
    sessionId = sessionId,
    eventSink = object : OpenCrayAgentRuntimeEventSink {
      override fun onRunEvent(task: AgentTask, event: OpenCrayAgentRunEvent) {
        recordRunEvent(event)
        listenerProvider().forEach { listener ->
          listener.onRunEvent(sessionId = sessionId, task = task, event = event)
          when (event) {
            is com.opencray.runtime.OpenCrayToolCallEvent -> listener.onToolCall(
              sessionId = sessionId,
              task = task,
              turn = event.turn,
              call = event.call,
            )
            is com.opencray.runtime.OpenCrayToolResultEvent -> listener.onToolResult(
              sessionId = sessionId,
              task = task,
              turn = event.turn,
              call = event.call,
              result = event.result,
            )
            else -> Unit
          }
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
      recordRunResult(task = task, result = result)
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
  ): AgentRunSubmission {
    touch()
    val acceptedAtEpochMs = System.currentTimeMillis()
    val runId = "run-$sessionId-${UUID.randomUUID().toString().take(8)}"
    val task = AgentTask(
      id = "prompt-$sessionId-${UUID.randomUUID().toString().take(8)}",
      type = com.opencray.core.contracts.AgentTaskType.PROMPT,
      input = userText,
      policyDecision = policyDecision,
      createdAtEpochMs = acceptedAtEpochMs,
      metadata = metadata + mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
        AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
        AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to visibleThroughMessageId,
      ),
    )
    val submittedTask = loop.submit(task)
    val submission = AgentRunSubmission(
      sessionId = sessionId,
      runId = runId,
      taskId = submittedTask.id,
      acceptedAtEpochMs = acceptedAtEpochMs,
    )
    synchronized(runLock) {
      runRecordsById[runId] = ManagedRunRecord(submission = submission)
    }
    return submission
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

  override fun requestResumeTask(taskId: String): Boolean {
    touch()
    val resumed = loop.requestResumeTask(taskId)
    if (resumed) {
      ensureProcessing()
    }
    return resumed
  }

  override fun listRuns(): List<AgentRunSnapshot> {
    touch()
    return currentRunSnapshots()
  }

  override fun findRun(runId: String): AgentRunSnapshot? {
    touch()
    return currentRunSnapshots().firstOrNull { snapshot -> snapshot.runId == runId }
  }

  override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? {
    touch()
    val boundedTimeoutMs = timeoutMs.coerceAtLeast(0L)
    val deadline = System.currentTimeMillis() + boundedTimeoutMs
    while (true) {
      val snapshot = findRun(runId)
      if (snapshot?.isTerminal == true) {
        return snapshot
      }
      val now = System.currentTimeMillis()
      if (now >= deadline) {
        return snapshot
      }
      val sleepMs = minOf(RUN_WAIT_POLL_INTERVAL_MS, deadline - now).coerceAtLeast(1L)
      try {
        Thread.sleep(sleepMs)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return snapshot
      }
    }
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

      AgentTaskState.SUSPENDED,
      AgentTaskState.COMPLETED,
      AgentTaskState.CANCELLED,
      AgentTaskState.FAILED,
      -> false
    }
  }

  fun touch() {
    lastAccessEpochMs = System.currentTimeMillis()
  }

  private fun recordRunEvent(event: OpenCrayAgentRunEvent) {
    synchronized(runLock) {
      val existing = runRecordsById[event.runId]
      if (existing != null) {
        runRecordsById[event.runId] = existing.copy(lastEvent = event)
      }
    }
  }

  private fun recordRunResult(task: AgentTask, result: ExecutionResult) {
    val runId = runIdFor(task)
    synchronized(runLock) {
      val existing = runRecordsById[runId]
      if (existing != null) {
        runRecordsById[runId] = existing.copy(lastResult = result)
      }
    }
  }

  private fun currentRunSnapshots(): List<AgentRunSnapshot> {
    val queueSnapshot = loop.snapshot()
    val taskSnapshotsByRunId = queueSnapshot.tasks.associateBy { taskSnapshot ->
      runIdFor(taskSnapshot.task)
    }
    val records = synchronized(runLock) { runRecordsById.toMap() }
    val runIds = linkedSetOf<String>().apply {
      addAll(records.keys)
      addAll(taskSnapshotsByRunId.keys)
    }
    return runIds.map { runId ->
      val record = records[runId]
      val taskSnapshot = taskSnapshotsByRunId[runId]
      val result = record?.lastResult
      val taskId = taskSnapshot?.task?.id ?: record?.submission?.taskId ?: runId
      val acceptedAtEpochMs = record?.submission?.acceptedAtEpochMs
        ?: taskSnapshot?.task?.createdAtEpochMs
        ?: 0L
      val updatedAtEpochMs = maxOf(
        taskSnapshot?.task?.updatedAtEpochMs ?: 0L,
        result?.finishedAtEpochMs ?: 0L,
        record?.lastEvent?.emittedAtEpochMs ?: 0L,
        acceptedAtEpochMs,
      )
      AgentRunSnapshot(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = acceptedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        lifecycleState = taskSnapshot?.lifecycleState,
        taskState = taskSnapshot?.task?.state,
        attempt = taskSnapshot?.attempt ?: 0,
        executionStatus = result?.status,
        errorCode = result?.errorCode ?: taskSnapshot?.lastErrorCode,
        errorMessage = result?.errorMessage ?: taskSnapshot?.lastErrorMessage,
        responseFormat = result?.metadata?.get("responseFormat"),
        resultMetadata = result?.metadata.orEmpty(),
        pendingMessageId = taskSnapshot?.task?.metadata
          ?.get(AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID),
        lastEvent = record?.lastEvent,
      )
    }.sortedByDescending { snapshot -> snapshot.acceptedAtEpochMs }
  }

  private fun runIdFor(task: AgentTask): String =
    task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.takeIf(String::isNotBlank)
      ?: task.id

  private data class ManagedRunRecord(
    val submission: AgentRunSubmission,
    val lastEvent: OpenCrayAgentRunEvent? = null,
    val lastResult: ExecutionResult? = null,
  )

  private companion object {
    const val RUN_WAIT_POLL_INTERVAL_MS: Long = 50L
  }
}
