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
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
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
  val managedProcessIds: List<String> = emptyList(),
  val runningManagedProcessCount: Int = 0,
  val hasLiveManagedProcesses: Boolean = false,
  val lastEvent: OpenCrayAgentRunEvent? = null,
) {
  // Host listeners observe runtime results before SessionQueue settles lifecycle transitions.
  private val hasTerminalExecutionStatus: Boolean
    get() = when (executionStatus) {
      null -> false
      ExecutionStatus.SUCCESS,
      ExecutionStatus.FAILED,
      ExecutionStatus.CANCELLED,
      ExecutionStatus.TIMEOUT,
      -> true

      ExecutionStatus.DENIED -> !isApprovalRequiredDenial
    }

  private val isApprovalRequiredDenial: Boolean
    get() = executionStatus == ExecutionStatus.DENIED &&
      (errorCode == ERROR_APPROVAL_REQUIRED || errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED)

  val isTerminal: Boolean
    get() = when (lifecycleState) {
      QueueTaskLifecycleState.RETRY_PENDING,
      QueueTaskLifecycleState.SUSPENDED,
      -> false

      QueueTaskLifecycleState.COMPLETED,
      QueueTaskLifecycleState.FAILED,
      QueueTaskLifecycleState.CANCELLED,
      -> true

      QueueTaskLifecycleState.QUEUED,
      QueueTaskLifecycleState.RUNNING,
      QueueTaskLifecycleState.CANCEL_REQUESTED,
      null,
      -> hasTerminalExecutionStatus
    }

  val isActive: Boolean
    get() = !isTerminal || hasLiveManagedProcesses
}

private const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
private const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
internal const val ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE: String = "PROCESS_INTERRUPTED_ON_RESTORE"
internal const val METADATA_RESTORED_TERMINAL_STATE: String = "restoredTerminalState"
internal const val METADATA_RESTORED_FROM_DURABLE_STORE: String = "restoredFromDurableStore"
internal const val METADATA_RUN_REPAIR_SOURCE: String = "runRepairSource"
internal const val RUN_REPAIR_SOURCE_MANAGED_PROCESS_RESTORE: String = "managed_process_restore"
internal const val RESTORED_TERMINAL_STATE_INTERRUPTED: String = "interrupted"

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

  fun submitTask(task: AgentTask): AgentRunSubmission =
    throw UnsupportedOperationException("submitTask is not supported by this runtime handle.")

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

  fun listManagedProcesses(): List<ManagedProcessSnapshot> = emptyList()

  fun hasLiveManagedProcesses(): Boolean =
    listManagedProcesses().any { snapshot -> snapshot.status == ManagedProcessStatus.RUNNING }

  fun terminateRunningManagedProcesses(): List<ManagedProcessSnapshot> = emptyList()
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

  fun listManagedProcesses(sessionId: String): List<ManagedProcessSnapshot> = emptyList()

  fun terminateManagedProcess(
    sessionId: String,
    processId: String,
  ): ManagedProcessSnapshot? = null
}

internal class DefaultAgentSessionRuntimeManager(
  private val agentId: String,
  private val runtimeFactory: AgentSessionTaskRuntimeFactory,
  private val snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  private val runRecordStoreFactory: AgentRunRecordStoreFactory,
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
        runRecordStore = runRecordStoreFactory.forChatSession(sessionId),
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
        if (!entry.value.hasPendingWork() && !entry.value.hasLiveManagedProcesses()) {
          iterator.remove()
        }
      }
    }
  }
}

private class ManagedAgentSessionHandle(
  override val sessionId: String,
  private val agentId: String,
  private val runtimeFactory: AgentSessionTaskRuntimeFactory,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  private val runRecordStore: AgentRunRecordStore,
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

  init {
    synchronized(runLock) {
      restorePersistedRunRecordsLocked()
      seedMissingRunRecordsLocked(loop.snapshot())
    }
  }

  override fun submitPrompt(
    userText: String,
    pendingMessageId: String,
    visibleThroughMessageId: String,
    policyDecision: PolicyDecision,
    metadata: Map<String, String>,
  ): AgentRunSubmission {
    touch()
    val task = AgentTask(
      id = "prompt-$sessionId-${UUID.randomUUID().toString().take(8)}",
      type = com.opencray.core.contracts.AgentTaskType.PROMPT,
      input = userText,
      policyDecision = policyDecision,
      createdAtEpochMs = System.currentTimeMillis(),
      metadata = metadata + mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to "run-$sessionId-${UUID.randomUUID().toString().take(8)}",
        AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
        AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to visibleThroughMessageId,
      ),
    )
    return submitTask(task)
  }

  override fun submitTask(task: AgentTask): AgentRunSubmission {
    touch()
    val acceptedAtEpochMs = maxOf(System.currentTimeMillis(), task.createdAtEpochMs)
    val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.takeIf(String::isNotBlank)
      ?: "run-$sessionId-${UUID.randomUUID().toString().take(8)}"
    val queuedTask = if (
      task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?.takeIf(String::isNotBlank) == runId
    ) {
      task
    } else {
      task.copy(
        metadata = task.metadata + mapOf(
          AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
          AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
        ),
      )
    }
    val submittedTask = loop.submit(queuedTask)
    val submission = AgentRunSubmission(
      sessionId = sessionId,
      runId = runId,
      taskId = submittedTask.id,
      acceptedAtEpochMs = acceptedAtEpochMs,
    )
    synchronized(runLock) {
      val record = ManagedRunRecord(
        submission = submission,
        pendingMessageId = submittedTask.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
      )
      runRecordsById[runId] = record
      persistRunRecordLocked(record)
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

  override fun hasPendingWork(): Boolean = currentRunSnapshots().any { snapshot -> !snapshot.isTerminal }

  override fun listManagedProcesses(): List<ManagedProcessSnapshot> =
    runtimeFactory.listManagedProcesses(sessionId)

  override fun terminateRunningManagedProcesses(): List<ManagedProcessSnapshot> =
    listManagedProcesses()
      .filter { snapshot -> snapshot.status == ManagedProcessStatus.RUNNING }
      .mapNotNull { snapshot ->
        runtimeFactory.terminateManagedProcess(
          sessionId = sessionId,
          processId = snapshot.processId,
        )
      }

  fun touch() {
    lastAccessEpochMs = System.currentTimeMillis()
  }

  private fun recordRunEvent(event: OpenCrayAgentRunEvent) {
    synchronized(runLock) {
      val existing = runRecordsById[event.runId] ?: ManagedRunRecord(
        submission = AgentRunSubmission(
          sessionId = sessionId,
          runId = event.runId,
          taskId = event.taskId,
          acceptedAtEpochMs = event.emittedAtEpochMs,
        ),
      )
      val updated = existing.copy(
        lastEvent = event,
        managedProcessIds = mergeManagedProcessIds(
          existing = existing.managedProcessIds,
          candidate = managedProcessIdFrom(event),
        ),
      )
      runRecordsById[event.runId] = updated
      persistRunRecordLocked(updated)
    }
  }

  private fun recordRunResult(task: AgentTask, result: ExecutionResult) {
    val runId = runIdFor(task)
    synchronized(runLock) {
      val existing = runRecordsById[runId] ?: ManagedRunRecord(
        submission = AgentRunSubmission(
          sessionId = sessionId,
          runId = runId,
          taskId = task.id,
          acceptedAtEpochMs = task.createdAtEpochMs,
        ),
        pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
      )
      val updated = existing.copy(
        pendingMessageId = existing.pendingMessageId
          ?: task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
        lastResult = result,
      )
      runRecordsById[runId] = updated
      persistRunRecordLocked(updated)
    }
  }

  private fun currentRunSnapshots(): List<AgentRunSnapshot> {
    val queueSnapshot = loop.snapshot()
    val managedProcesses = listManagedProcesses()
    synchronized(runLock) {
      seedMissingRunRecordsLocked(queueSnapshot)
      repairRestoredInterruptedRunsLocked(
        queueSnapshot = queueSnapshot,
        managedProcesses = managedProcesses,
      )
    }
    val managedProcessesById = managedProcesses.associateBy(ManagedProcessSnapshot::processId)
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
      val managedProcessIds = associatedManagedProcessIds(
        taskId = taskId,
        existingIds = record?.managedProcessIds.orEmpty(),
        managedProcessesById = managedProcessesById,
      )
      val runningManagedProcessCount = managedProcessIds.count { processId ->
        managedProcessesById[processId]?.status == ManagedProcessStatus.RUNNING
      }
      val acceptedAtEpochMs = record?.submission?.acceptedAtEpochMs
        ?: taskSnapshot?.task?.createdAtEpochMs
        ?: 0L
      val updatedAtEpochMs = maxOf(
        taskSnapshot?.task?.updatedAtEpochMs ?: 0L,
        result?.finishedAtEpochMs ?: 0L,
        record?.lastEvent?.emittedAtEpochMs ?: 0L,
        managedProcessIds.maxOfOrNull { processId ->
          managedProcessesById[processId]?.updatedAtEpochMs ?: 0L
        } ?: 0L,
        acceptedAtEpochMs,
      )
      val projectedLifecycleState = projectedLifecycleState(
        original = taskSnapshot?.lifecycleState,
        result = result,
      )
      val projectedTaskState = projectedTaskState(
        original = taskSnapshot?.task?.state,
        result = result,
      )
      AgentRunSnapshot(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = acceptedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        lifecycleState = projectedLifecycleState,
        taskState = projectedTaskState,
        attempt = taskSnapshot?.attempt ?: 0,
        executionStatus = result?.status,
        errorCode = result?.errorCode ?: taskSnapshot?.lastErrorCode,
        errorMessage = result?.errorMessage ?: taskSnapshot?.lastErrorMessage,
        responseFormat = result?.metadata?.get("responseFormat"),
        resultMetadata = result?.metadata.orEmpty(),
        pendingMessageId = taskSnapshot?.task?.metadata
          ?.get(AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID)
          ?: record?.pendingMessageId,
        managedProcessIds = managedProcessIds,
        runningManagedProcessCount = runningManagedProcessCount,
        hasLiveManagedProcesses = runningManagedProcessCount > 0,
        lastEvent = record?.lastEvent,
      )
    }.sortedByDescending { snapshot -> snapshot.acceptedAtEpochMs }
  }

  private fun repairRestoredInterruptedRunsLocked(
    queueSnapshot: SessionQueueSnapshot,
    managedProcesses: List<ManagedProcessSnapshot>,
  ) {
    val managedProcessesById = managedProcesses.associateBy(ManagedProcessSnapshot::processId)
    val taskSnapshotsByRunId = queueSnapshot.tasks.associateBy { taskSnapshot ->
      runIdFor(taskSnapshot.task)
    }
    runRecordsById.entries.toList().forEach { (runId, record) ->
      val taskSnapshot = taskSnapshotsByRunId[runId]
      val taskId = taskSnapshot?.task?.id ?: record.submission.taskId
      val associatedProcesses = associatedManagedProcesses(
        taskId = taskId,
        existingIds = record.managedProcessIds,
        managedProcessesById = managedProcessesById,
      )
      if (!shouldRepairRestoredInterruptedRun(taskSnapshot, record, associatedProcesses)) {
        return@forEach
      }
      val updated = record.copy(
        managedProcessIds = (
          record.managedProcessIds +
            associatedProcesses.map(ManagedProcessSnapshot::processId)
          ).distinct(),
        lastResult = repairedInterruptedRestoreResult(
          record = record,
          associatedProcesses = associatedProcesses,
        ),
      )
      if (updated != record) {
        runRecordsById[runId] = updated
        persistRunRecordLocked(updated)
      }
    }
  }

  private fun restorePersistedRunRecordsLocked() {
    runRecordStore.list().forEach { persisted ->
      runRecordsById[persisted.runId] = ManagedRunRecord(
        submission = AgentRunSubmission(
          sessionId = sessionId,
          runId = persisted.runId,
          taskId = persisted.taskId,
          acceptedAtEpochMs = persisted.acceptedAtEpochMs,
        ),
        pendingMessageId = persisted.pendingMessageId,
        managedProcessIds = persisted.managedProcessIds,
        lastEvent = persisted.lastEvent?.toRuntimeEvent(),
        lastResult = persisted.lastResult,
      )
    }
  }

  private fun seedMissingRunRecordsLocked(queueSnapshot: SessionQueueSnapshot) {
    queueSnapshot.tasks.forEach { taskSnapshot ->
      val runId = runIdFor(taskSnapshot.task)
      val existing = runRecordsById[runId]
      if (existing == null) {
        val seeded = ManagedRunRecord(
          submission = AgentRunSubmission(
            sessionId = sessionId,
            runId = runId,
            taskId = taskSnapshot.task.id,
            acceptedAtEpochMs = taskSnapshot.task.createdAtEpochMs,
          ),
          pendingMessageId = taskSnapshot.task.metadata[
            AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID
          ],
        )
        runRecordsById[runId] = seeded
        persistRunRecordLocked(seeded)
      } else if (existing.pendingMessageId == null) {
        val updated = existing.copy(
          pendingMessageId = taskSnapshot.task.metadata[
            AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID
          ],
        )
        runRecordsById[runId] = updated
        persistRunRecordLocked(updated)
      }
    }
  }

  private fun persistRunRecordLocked(record: ManagedRunRecord) {
    runRecordStore.upsert(
      PersistedAgentRunRecord(
        runId = record.submission.runId,
        taskId = record.submission.taskId,
        acceptedAtEpochMs = record.submission.acceptedAtEpochMs,
        pendingMessageId = record.pendingMessageId,
        managedProcessIds = record.managedProcessIds,
        lastResult = record.lastResult,
        lastEvent = record.lastEvent?.toPersistedRecord(),
      ),
    )
  }

  private fun managedProcessIdFrom(event: OpenCrayAgentRunEvent): String? =
    (event as? com.opencray.runtime.OpenCrayToolResultEvent)
      ?.result
      ?.metadata
      ?.get("processId")
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun mergeManagedProcessIds(
    existing: List<String>,
    candidate: String?,
  ): List<String> = listOfNotNull(candidate)
    .fold(existing) { current, processId ->
      if (processId in current) current else current + processId
    }

  private fun shouldRepairRestoredInterruptedRun(
    taskSnapshot: com.opencray.core.orchestrator.SessionQueueTaskSnapshot?,
    record: ManagedRunRecord,
    associatedProcesses: List<ManagedProcessSnapshot>,
  ): Boolean {
    if (record.lastResult != null) {
      return false
    }
    if (taskSnapshot == null || isTerminalLifecycle(taskSnapshot.lifecycleState)) {
      return false
    }
    if (associatedProcesses.isEmpty()) {
      return false
    }
    if (associatedProcesses.any { snapshot -> snapshot.status == ManagedProcessStatus.RUNNING }) {
      return false
    }
    return associatedProcesses.all { snapshot -> snapshot.isTerminalAfterRestore() } &&
      associatedProcesses.any { snapshot -> snapshot.isInterruptedOnRestore() }
  }

  private fun repairedInterruptedRestoreResult(
    record: ManagedRunRecord,
    associatedProcesses: List<ManagedProcessSnapshot>,
  ): ExecutionResult {
    val orderedProcessIds = associatedProcesses
      .map(ManagedProcessSnapshot::processId)
      .distinct()
      .sorted()
    val latestUpdateEpochMs = associatedProcesses.maxOf { snapshot ->
      snapshot.finishedAtEpochMs ?: snapshot.updatedAtEpochMs
    }
    val startedAtEpochMs = record.submission.acceptedAtEpochMs
    val finishedAtEpochMs = maxOf(startedAtEpochMs, latestUpdateEpochMs)
    return ExecutionResult(
      taskId = record.submission.taskId,
      status = ExecutionStatus.FAILED,
      errorCode = ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE,
      errorMessage = buildString {
        append("Managed process state was restored in an interrupted terminal state")
        if (orderedProcessIds.isNotEmpty()) {
          append(" for ")
          append(orderedProcessIds.joinToString(", "))
        }
        append("; marking the run interrupted until the user decides how to continue.")
      },
      startedAtEpochMs = startedAtEpochMs,
      finishedAtEpochMs = finishedAtEpochMs,
      metadata = mapOf(
        METADATA_RESTORED_TERMINAL_STATE to RESTORED_TERMINAL_STATE_INTERRUPTED,
        METADATA_RESTORED_FROM_DURABLE_STORE to "true",
        METADATA_RUN_REPAIR_SOURCE to RUN_REPAIR_SOURCE_MANAGED_PROCESS_RESTORE,
        "managedProcessIds" to orderedProcessIds.joinToString(","),
      ),
    )
  }

  private fun associatedManagedProcessIds(
    taskId: String,
    existingIds: List<String>,
    managedProcessesById: Map<String, ManagedProcessSnapshot>,
  ): List<String> = (
    existingIds +
      managedProcessesById.values
        .asSequence()
        .filter { snapshot -> snapshot.taskId == taskId }
        .map(ManagedProcessSnapshot::processId)
        .toList()
    ).distinct()

  private fun associatedManagedProcesses(
    taskId: String,
    existingIds: List<String>,
    managedProcessesById: Map<String, ManagedProcessSnapshot>,
  ): List<ManagedProcessSnapshot> = associatedManagedProcessIds(
    taskId = taskId,
    existingIds = existingIds,
    managedProcessesById = managedProcessesById,
  ).mapNotNull(managedProcessesById::get)

  private fun projectedLifecycleState(
    original: QueueTaskLifecycleState?,
    result: ExecutionResult?,
  ): QueueTaskLifecycleState? = if (
    isInterruptedOnRestoreResult(result) &&
    (original == null || !isTerminalLifecycle(original))
  ) {
    QueueTaskLifecycleState.FAILED
  } else {
    original
  }

  private fun projectedTaskState(
    original: AgentTaskState?,
    result: ExecutionResult?,
  ): AgentTaskState? = if (
    isInterruptedOnRestoreResult(result) &&
    (original == null || !isTerminalTaskState(original))
  ) {
    AgentTaskState.FAILED
  } else {
    original
  }

  private fun isInterruptedOnRestoreResult(result: ExecutionResult?): Boolean =
    result?.errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE &&
      result.metadata[METADATA_RESTORED_TERMINAL_STATE] == RESTORED_TERMINAL_STATE_INTERRUPTED

  private fun isTerminalLifecycle(state: QueueTaskLifecycleState): Boolean = when (state) {
    QueueTaskLifecycleState.COMPLETED,
    QueueTaskLifecycleState.FAILED,
    QueueTaskLifecycleState.CANCELLED,
    -> true

    QueueTaskLifecycleState.QUEUED,
    QueueTaskLifecycleState.RUNNING,
    QueueTaskLifecycleState.RETRY_PENDING,
    QueueTaskLifecycleState.SUSPENDED,
    QueueTaskLifecycleState.CANCEL_REQUESTED,
    -> false
  }

  private fun isTerminalTaskState(state: AgentTaskState): Boolean = when (state) {
    AgentTaskState.COMPLETED,
    AgentTaskState.CANCELLED,
    AgentTaskState.FAILED,
    -> true

    AgentTaskState.QUEUED,
    AgentTaskState.RUNNING,
    AgentTaskState.SUSPENDED,
    -> false
  }

  private fun ManagedProcessSnapshot.isInterruptedOnRestore(): Boolean =
    errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE ||
      metadata[METADATA_RESTORED_TERMINAL_STATE] == RESTORED_TERMINAL_STATE_INTERRUPTED

  private fun ManagedProcessSnapshot.isTerminalAfterRestore(): Boolean = status.isTerminal

  private fun runIdFor(task: AgentTask): String =
    task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.takeIf(String::isNotBlank)
      ?: task.id

  private data class ManagedRunRecord(
    val submission: AgentRunSubmission,
    val pendingMessageId: String? = null,
    val managedProcessIds: List<String> = emptyList(),
    val lastEvent: OpenCrayAgentRunEvent? = null,
    val lastResult: ExecutionResult? = null,
  )

  private companion object {
    const val RUN_WAIT_POLL_INTERVAL_MS: Long = 50L
  }
}
