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
  val mailboxDeliveryCursorBeforeCurrentTurn: String? = null,
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

  fun allClosedHandles(): List<SubAgentHandleState> = emptyList()

  fun handlesForParentRun(parentRunId: String): List<SubAgentHandleState> = allHandles().filter { handle ->
    handle.parentRunId == parentRunId
  }

  fun currentHandle(key: SubAgentExecutionKey): SubAgentHandleState?

  fun closedHandle(key: SubAgentExecutionKey): SubAgentHandleState? = null

  fun upsertHandle(handle: SubAgentHandleState): SubAgentHandleState

  fun noteClosedHandle(handle: SubAgentHandleState): SubAgentHandleState = handle

  fun upsertHandleIfOwnedByExecution(
    handle: SubAgentHandleState,
    expectedExecution: SubAgentActiveExecution,
  ): SubAgentHandleState?

  fun removeHandle(key: SubAgentExecutionKey): SubAgentHandleState?

  fun updateHandle(
    key: SubAgentExecutionKey,
    transform: (SubAgentHandleState) -> SubAgentHandleState,
  ): SubAgentHandleState? {
    val current = currentHandle(key) ?: return null
    return upsertHandle(transform(current))
  }

  fun retainKnownParentRuns(parentRunIds: Set<String>) = Unit

  fun activeExecution(key: SubAgentExecutionKey): SubAgentActiveExecution?

  fun registerActiveExecution(
    key: SubAgentExecutionKey,
    execution: SubAgentActiveExecution,
  ): SubAgentActiveExecution?

  fun takeActiveExecution(
    key: SubAgentExecutionKey,
    expectedExecution: SubAgentActiveExecution? = null,
  ): SubAgentActiveExecution?

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
    expectedExecution: SubAgentActiveExecution? = null,
  ): SubAgentHandleState? {
    val key = SubAgentExecutionKey.from(handle)
    if (expectedExecution != null && activeExecution(key) !== expectedExecution) {
      return null
    }
    takeActiveExecution(key, expectedExecution = expectedExecution)
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
  private val closedHandlesByKey = linkedMapOf<SubAgentExecutionKey, SubAgentHandleState>()
  private val activeExecutionsByKey = linkedMapOf<SubAgentExecutionKey, SubAgentActiveExecution>()

  override fun allHandles(): List<SubAgentHandleState> = synchronized(lock) {
    handlesByKey.values
      .sortedByDescending(SubAgentHandleState::updatedAtEpochMs)
  }

  override fun allClosedHandles(): List<SubAgentHandleState> = synchronized(lock) {
    closedHandlesByKey.values
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

  override fun closedHandle(key: SubAgentExecutionKey): SubAgentHandleState? = synchronized(lock) {
    closedHandlesByKey[key]
  }

  override fun upsertHandle(handle: SubAgentHandleState): SubAgentHandleState = synchronized(lock) {
    val key = SubAgentExecutionKey.from(handle)
    closedHandlesByKey.remove(key)
    handlesByKey[key] = handle
    handle
  }

  override fun noteClosedHandle(handle: SubAgentHandleState): SubAgentHandleState = synchronized(lock) {
    closedHandlesByKey[SubAgentExecutionKey.from(handle)] = handle
    handle
  }

  override fun upsertHandleIfOwnedByExecution(
    handle: SubAgentHandleState,
    expectedExecution: SubAgentActiveExecution,
  ): SubAgentHandleState? = synchronized(lock) {
    val key = SubAgentExecutionKey.from(handle)
    val activeExecution = activeExecutionsByKey[key] ?: return@synchronized null
    if (activeExecution !== expectedExecution) {
      return@synchronized null
    }
    handlesByKey[key] = handle
    handle
  }

  override fun removeHandle(key: SubAgentExecutionKey): SubAgentHandleState? = synchronized(lock) {
    handlesByKey.remove(key)
  }

  override fun retainKnownParentRuns(parentRunIds: Set<String>) {
    if (parentRunIds.isEmpty()) {
      synchronized(lock) {
        handlesByKey.clear()
        closedHandlesByKey.clear()
        activeExecutionsByKey.clear()
      }
      return
    }
    synchronized(lock) {
      handlesByKey.entries.removeIf { (_, handle) ->
        handle.parentRunId !in parentRunIds
      }
      closedHandlesByKey.entries.removeIf { (_, handle) ->
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

  override fun takeActiveExecution(
    key: SubAgentExecutionKey,
    expectedExecution: SubAgentActiveExecution?,
  ): SubAgentActiveExecution? = synchronized(lock) {
    val existingExecution = activeExecutionsByKey[key] ?: return@synchronized null
    if (expectedExecution != null && existingExecution !== expectedExecution) {
      return@synchronized null
    }
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
      closedHandlesByKey.remove(key)
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
    expectedExecution: SubAgentActiveExecution?,
  ): SubAgentHandleState? = synchronized(lock) {
    val key = SubAgentExecutionKey.from(handle)
    if (expectedExecution != null) {
      val activeExecution = activeExecutionsByKey[key] ?: return@synchronized null
      if (activeExecution !== expectedExecution) {
        return@synchronized null
      }
    }
    activeExecutionsByKey.remove(key)
    if (removeHandle) {
      handlesByKey.remove(key)
      null
    } else {
      closedHandlesByKey.remove(key)
      handlesByKey[key] = handle
      handle
    }
  }
}
