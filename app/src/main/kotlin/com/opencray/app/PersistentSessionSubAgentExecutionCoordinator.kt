package com.opencray.app

import com.opencray.runtime.subagent.SubAgentActiveExecution
import com.opencray.runtime.subagent.SubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentExecutionKey
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

  override fun takeActiveExecution(key: SubAgentExecutionKey): SubAgentActiveExecution? = synchronized(lock) {
    activeExecutionsByKey.remove(key)
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
