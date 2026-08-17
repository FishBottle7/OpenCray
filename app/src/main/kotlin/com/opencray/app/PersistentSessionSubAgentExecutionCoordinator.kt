package com.opencray.app

import com.opencray.runtime.subagent.SubAgentActiveExecution
import com.opencray.runtime.subagent.SubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentExecutionStartResult
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentSessionLink
import com.opencray.runtime.subagent.restoredInterruptedBackgroundSubAgentHandle

internal class PersistentSessionSubAgentExecutionCoordinator(
  private val sessionId: String,
  private val store: SubAgentHandleStore,
  private val linkStore: SubAgentSessionLinkStore,
  private val clock: () -> Long = { System.currentTimeMillis() },
) : SubAgentExecutionCoordinator {
  private val lock = Any()
  private val activeExecutionsByKey = linkedMapOf<SubAgentExecutionKey, SubAgentActiveExecution>()
  private val closedHandlesByKey = linkedMapOf<SubAgentExecutionKey, SubAgentHandleState>()

  init {
    repairInterruptedBackgroundHandles()
  }

  override fun allHandles(): List<SubAgentHandleState> = store.list()

  override fun allClosedHandles(): List<SubAgentHandleState> = synchronized(lock) {
    mergedClosedHandlesLocked()
  }

  override fun handlesForParentRun(parentRunId: String): List<SubAgentHandleState> =
    store.listForParentRun(parentRunId)

  override fun currentHandle(key: SubAgentExecutionKey): SubAgentHandleState? =
    store.get(parentRunId = key.parentRunId, agentId = key.agentId)

  override fun closedHandle(key: SubAgentExecutionKey): SubAgentHandleState? = synchronized(lock) {
    closedHandlesByKey[key]
      ?: store.getClosed(parentRunId = key.parentRunId, agentId = key.agentId)
  }

  override fun upsertHandle(handle: SubAgentHandleState): SubAgentHandleState {
    synchronized(lock) {
      closedHandlesByKey.remove(SubAgentExecutionKey.from(handle))
    }
    store.upsert(handle)
    syncLink(handle = handle, closed = false)
    return handle
  }

  override fun noteClosedHandle(handle: SubAgentHandleState): SubAgentHandleState {
    synchronized(lock) {
      closedHandlesByKey[SubAgentExecutionKey.from(handle)] = handle
    }
    store.upsertClosed(handle)
    syncLink(handle = handle, closed = true)
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
    closedHandlesByKey.remove(key)
    store.upsert(handle)
    syncLink(handle = handle, closed = false)
    handle
  }

  override fun removeHandle(key: SubAgentExecutionKey): SubAgentHandleState? =
    store.remove(parentRunId = key.parentRunId, agentId = key.agentId)?.also { removed ->
      syncLink(handle = removed, closed = true)
    }

  override fun retainKnownParentRuns(parentRunIds: Set<String>) {
    synchronized(lock) {
      if (parentRunIds.isEmpty()) {
        closedHandlesByKey.clear()
      } else {
        closedHandlesByKey.entries.removeIf { (_, handle) ->
          handle.parentRunId !in parentRunIds
        }
      }
    }
    store.retainKnownParentRuns(parentRunIds)
    linkStore.retainKnownParentRuns(parentRunIds)
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
      closedHandlesByKey.remove(key)
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
      closedHandlesByKey.remove(key)
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
        ).also { repaired ->
          syncLink(handle = repaired, closed = false)
        },
      )
    }
  }

  private fun syncLink(
    handle: SubAgentHandleState,
    closed: Boolean,
  ) {
    linkStore.upsert(
      SubAgentSessionLink.fromHandle(
        parentSessionId = sessionId,
        handle = handle,
        closed = closed,
      ),
    )
  }

  private fun mergedClosedHandlesLocked(): List<SubAgentHandleState> {
    val merged = linkedMapOf<SubAgentExecutionKey, SubAgentHandleState>()
    (store.listClosed() + closedHandlesByKey.values)
      .sortedByDescending(SubAgentHandleState::updatedAtEpochMs)
      .forEach { handle ->
        val key = SubAgentExecutionKey.from(handle)
        val existing = merged[key]
        if (existing == null || handle.updatedAtEpochMs >= existing.updatedAtEpochMs) {
          merged[key] = handle
        }
      }
    return merged.values
      .sortedByDescending(SubAgentHandleState::updatedAtEpochMs)
  }
}
