package com.opencray.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

internal enum class AgentTaskApprovalState {
  APPROVED,
  REJECTED,
}

internal class AgentTaskApprovalRegistry {
  private val statesBySession: ConcurrentMap<String, ConcurrentMap<String, AgentTaskApprovalState>> =
    ConcurrentHashMap()

  fun markApproved(sessionId: String, taskId: String) {
    sessionState(sessionId)[taskId] = AgentTaskApprovalState.APPROVED
  }

  fun markRejected(sessionId: String, taskId: String) {
    sessionState(sessionId)[taskId] = AgentTaskApprovalState.REJECTED
  }

  fun consumeApproved(sessionId: String, taskId: String): Boolean {
    val sessionState = statesBySession[sessionId] ?: return false
    return sessionState.remove(taskId) == AgentTaskApprovalState.APPROVED
  }

  fun isRejected(sessionId: String, taskId: String): Boolean {
    val sessionState = statesBySession[sessionId] ?: return false
    return sessionState[taskId] == AgentTaskApprovalState.REJECTED
  }

  fun clear(sessionId: String, taskId: String) {
    statesBySession[sessionId]?.remove(taskId)
  }

  fun retainKnownTasks(sessionId: String, taskIds: Set<String>) {
    val sessionState = statesBySession[sessionId] ?: return
    sessionState.keys.retainAll(taskIds)
    if (sessionState.isEmpty()) {
      statesBySession.remove(sessionId, sessionState)
    }
  }

  private fun sessionState(sessionId: String): ConcurrentMap<String, AgentTaskApprovalState> =
    statesBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
}
