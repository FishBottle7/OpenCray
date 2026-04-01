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
      }
      return
    }
    synchronized(lock) {
      handlesByKey.entries.removeIf { (_, handle) ->
        handle.parentRunId !in parentRunIds
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
}
