package com.opencray.app

import com.opencray.runtime.subagent.SubAgentActiveExecution
import com.opencray.runtime.subagent.SubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentExecutionStartResult
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.restoredInterruptedBackgroundSubAgentHandle

internal class PersistentSessionSubAgentExecutionCoordinator(
  private val store: SubAgentHandleStore,
  private val clock: () -> Long = { System.currentTimeMillis() },
) : SubAgentExecutionCoordinator {
  private val lock = Any()
  private val activeExecutionsByKey = linkedMapOf<SubAgentExecutionKey, SubAgentActiveExecution>()

  init {
    repairInterruptedBackgroundHandles()
  }

  override fun allHandles(): List<SubAgentHandleState> = store.list()

  override fun handlesForParentRun(parentRunId: String): List<SubAgentHandleState> =
    store.listForParentRun(parentRunId)

  override fun currentHandle(key: SubAgentExecutionKey): SubAgentHandleState? =
    store.get(parentRunId = key.parentRunId, agentId = key.agentId)

  override fun upsertHandle(handle: SubAgentHandleState): SubAgentHandleState {
    store.upsert(handle)
    return handle
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
    store.upsert(handle)
    handle
  }

  override fun removeHandle(key: SubAgentExecutionKey): SubAgentHandleState? =
    store.remove(parentRunId = key.parentRunId, agentId = key.agentId)

  override fun retainKnownParentRuns(parentRunIds: Set<String>) {
    store.retainKnownParentRuns(parentRunIds)
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
        handle = store.get(parentRunId = key.parentRunId, agentId = key.agentId) ?: handle,
        activeExecution = existingExecution,
      )
    } else {
      store.upsert(handle)
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
    return if (removeHandle) {
      store.remove(parentRunId = key.parentRunId, agentId = key.agentId)
      null
    } else {
      store.upsert(handle)
      handle
    }
  }

  private fun repairInterruptedBackgroundHandles() {
    store.list().forEach { handle ->
      if (handle.snapshot.state != SubAgentExecutionState.BACKGROUND_RUNNING) {
        return@forEach
      }
      val key = SubAgentExecutionKey.from(handle)
      if (activeExecution(key) != null) {
        return@forEach
      }
      store.upsert(
        restoredInterruptedBackgroundSubAgentHandle(
          handle = handle,
          restoredAtEpochMs = clock(),
        ),
      )
    }
  }
}
