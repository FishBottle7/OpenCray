package com.opencray.runtime.subagent

import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

data class SubAgentExecutionKey(
  val parentRunId: String,
  val agentId: String,
) {
  init {
    require(parentRunId.isNotBlank()) {
      "SubAgentExecutionKey parentRunId must not be blank."
    }
    require(agentId.isNotBlank()) {
      "SubAgentExecutionKey agentId must not be blank."
    }
  }

  val handleId: String
    get() = agentId

  companion object {
    fun from(handle: SubAgentHandleState): SubAgentExecutionKey = SubAgentExecutionKey(
      parentRunId = handle.parentRunId,
      agentId = handle.agentId,
    )
  }
}

data class SubAgentActiveExecution(
  val executor: ExecutorService,
  val future: Future<Unit>,
  val cancelRequested: AtomicBoolean,
  val closed: AtomicBoolean,
) {
  fun cancel(
    markClosed: Boolean = false,
  ) {
    if (markClosed) {
      closed.set(true)
    }
    cancelRequested.set(true)
    future.cancel(true)
    executor.shutdownNow()
  }
}

data class SubAgentExecutionStartResult(
  val started: Boolean,
  val handle: SubAgentHandleState,
  val activeExecution: SubAgentActiveExecution,
)

interface SubAgentExecutionCoordinator {
  fun allHandles(): List<SubAgentHandleState> = emptyList()

  fun handlesForParentRun(parentRunId: String): List<SubAgentHandleState> = allHandles().filter { handle ->
    handle.parentRunId == parentRunId
  }

  fun currentHandle(key: SubAgentExecutionKey): SubAgentHandleState?

  fun upsertHandle(handle: SubAgentHandleState): SubAgentHandleState

  fun removeHandle(key: SubAgentExecutionKey): SubAgentHandleState?

  fun retainKnownParentRuns(parentRunIds: Set<String>) = Unit

  fun activeExecution(key: SubAgentExecutionKey): SubAgentActiveExecution?

  fun registerActiveExecution(
    key: SubAgentExecutionKey,
    execution: SubAgentActiveExecution,
  ): SubAgentActiveExecution?

  fun takeActiveExecution(key: SubAgentExecutionKey): SubAgentActiveExecution?

  fun beginExecution(
    handle: SubAgentHandleState,
    execution: SubAgentActiveExecution,
  ): SubAgentExecutionStartResult {
    val key = SubAgentExecutionKey.from(handle)
    val existingExecution = registerActiveExecution(
      key = key,
      execution = execution,
    )
    return if (existingExecution != null) {
      SubAgentExecutionStartResult(
        started = false,
        handle = currentHandle(key) ?: handle,
        activeExecution = existingExecution,
      )
    } else {
      upsertHandle(handle)
      SubAgentExecutionStartResult(
        started = true,
        handle = handle,
        activeExecution = execution,
      )
    }
  }

  fun finishExecution(
    handle: SubAgentHandleState,
    removeHandle: Boolean = false,
  ): SubAgentHandleState? {
    val key = SubAgentExecutionKey.from(handle)
    takeActiveExecution(key)
    return if (removeHandle) {
      removeHandle(key)
      null
    } else {
      upsertHandle(handle)
    }
  }

  fun cancelActiveExecution(
    key: SubAgentExecutionKey,
    markClosed: Boolean = false,
  ): SubAgentActiveExecution? = takeActiveExecution(key)?.also { execution ->
    execution.cancel(markClosed = markClosed)
  }
}

class InMemorySubAgentExecutionCoordinator : SubAgentExecutionCoordinator {
  private val lock = Any()
  private val handlesByKey = linkedMapOf<SubAgentExecutionKey, SubAgentHandleState>()
  private val activeExecutionsByKey = linkedMapOf<SubAgentExecutionKey, SubAgentActiveExecution>()

  override fun allHandles(): List<SubAgentHandleState> = synchronized(lock) {
    handlesByKey.values
      .sortedByDescending(SubAgentHandleState::updatedAtEpochMs)
  }

  override fun handlesForParentRun(parentRunId: String): List<SubAgentHandleState> = synchronized(lock) {
    handlesByKey.values
      .filter { handle -> handle.parentRunId == parentRunId }
      .sortedByDescending(SubAgentHandleState::updatedAtEpochMs)
  }

  override fun currentHandle(key: SubAgentExecutionKey): SubAgentHandleState? = synchronized(lock) {
    handlesByKey[key]
  }

  override fun upsertHandle(handle: SubAgentHandleState): SubAgentHandleState = synchronized(lock) {
    handlesByKey[SubAgentExecutionKey.from(handle)] = handle
    handle
  }

  override fun removeHandle(key: SubAgentExecutionKey): SubAgentHandleState? = synchronized(lock) {
    handlesByKey.remove(key)
  }

  override fun retainKnownParentRuns(parentRunIds: Set<String>) {
    if (parentRunIds.isEmpty()) {
      synchronized(lock) {
        handlesByKey.clear()
        activeExecutionsByKey.clear()
      }
      return
    }
    synchronized(lock) {
      handlesByKey.entries.removeIf { (_, handle) ->
        handle.parentRunId !in parentRunIds
      }
      activeExecutionsByKey.entries.removeIf { (key, _) ->
        key.parentRunId !in parentRunIds
      }
    }
  }

  override fun activeExecution(key: SubAgentExecutionKey): SubAgentActiveExecution? = synchronized(lock) {
    activeExecutionsByKey[key]
  }

  override fun registerActiveExecution(
    key: SubAgentExecutionKey,
    execution: SubAgentActiveExecution,
  ): SubAgentActiveExecution? = synchronized(lock) {
    val existing = activeExecutionsByKey[key]
    if (existing != null) {
      existing
    } else {
      activeExecutionsByKey[key] = execution
      null
    }
  }

  override fun takeActiveExecution(key: SubAgentExecutionKey): SubAgentActiveExecution? = synchronized(lock) {
    activeExecutionsByKey.remove(key)
  }

  override fun beginExecution(
    handle: SubAgentHandleState,
    execution: SubAgentActiveExecution,
  ): SubAgentExecutionStartResult = synchronized(lock) {
    val key = SubAgentExecutionKey.from(handle)
    val existingExecution = activeExecutionsByKey[key]
    if (existingExecution != null) {
      SubAgentExecutionStartResult(
        started = false,
        handle = handlesByKey[key] ?: handle,
        activeExecution = existingExecution,
      )
    } else {
      handlesByKey[key] = handle
      activeExecutionsByKey[key] = execution
      SubAgentExecutionStartResult(
        started = true,
        handle = handle,
        activeExecution = execution,
      )
    }
  }

  override fun finishExecution(
    handle: SubAgentHandleState,
    removeHandle: Boolean,
  ): SubAgentHandleState? = synchronized(lock) {
    val key = SubAgentExecutionKey.from(handle)
    activeExecutionsByKey.remove(key)
    if (removeHandle) {
      handlesByKey.remove(key)
      null
    } else {
      handlesByKey[key] = handle
      handle
    }
  }
}
