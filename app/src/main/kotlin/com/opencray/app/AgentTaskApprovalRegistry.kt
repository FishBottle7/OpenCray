package com.opencray.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

internal enum class AgentTaskApprovalState {
  APPROVED,
  REJECTED,
}

internal data class AgentTaskApprovalGrant(
  val taskId: String,
  val toolName: String? = null,
)

internal class AgentTaskApprovalRegistry {
  private val statesBySession: ConcurrentMap<String, ConcurrentMap<String, AgentTaskApprovalState>> =
    ConcurrentHashMap()
  private val approvedToolNamesBySession: ConcurrentMap<String, ConcurrentMap<String, String>> =
    ConcurrentHashMap()

  fun markApproved(sessionId: String, taskId: String, toolName: String? = null) {
    sessionState(sessionId)[taskId] = AgentTaskApprovalState.APPROVED
    val toolNames = approvedToolNamesBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
    if (toolName.isNullOrBlank()) {
      toolNames.remove(taskId)
      if (toolNames.isEmpty()) {
        approvedToolNamesBySession.remove(sessionId, toolNames)
      }
    } else {
      toolNames[taskId] = toolName
    }
  }

  fun markRejected(sessionId: String, taskId: String) {
    sessionState(sessionId)[taskId] = AgentTaskApprovalState.REJECTED
    approvedToolNamesBySession[sessionId]?.remove(taskId)
  }

  fun consumeApproved(sessionId: String, taskId: String): AgentTaskApprovalGrant? {
    val sessionState = statesBySession[sessionId] ?: return null
    val approved = sessionState.remove(taskId) == AgentTaskApprovalState.APPROVED
    val toolNames = approvedToolNamesBySession[sessionId]
    val toolName = toolNames?.remove(taskId)
    if (sessionState.isEmpty()) {
      statesBySession.remove(sessionId, sessionState)
    }
    if (toolNames != null && toolNames.isEmpty()) {
      approvedToolNamesBySession.remove(sessionId, toolNames)
    }
    return if (approved) {
      AgentTaskApprovalGrant(taskId = taskId, toolName = toolName)
    } else {
      null
    }
  }

  fun isRejected(sessionId: String, taskId: String): Boolean {
    val sessionState = statesBySession[sessionId] ?: return false
    return sessionState[taskId] == AgentTaskApprovalState.REJECTED
  }

  fun isApproved(sessionId: String, taskId: String): Boolean {
    val sessionState = statesBySession[sessionId] ?: return false
    return sessionState[taskId] == AgentTaskApprovalState.APPROVED
  }

  fun clear(sessionId: String, taskId: String) {
    statesBySession[sessionId]?.remove(taskId)
    approvedToolNamesBySession[sessionId]?.remove(taskId)
  }

  fun retainKnownTasks(sessionId: String, taskIds: Set<String>) {
    val sessionState = statesBySession[sessionId] ?: return
    sessionState.keys.retainAll(taskIds)
    approvedToolNamesBySession[sessionId]?.let { toolNames ->
      toolNames.keys.retainAll(taskIds)
      if (toolNames.isEmpty()) {
        approvedToolNamesBySession.remove(sessionId, toolNames)
      }
    }
    if (sessionState.isEmpty()) {
      statesBySession.remove(sessionId, sessionState)
    }
  }

  private fun sessionState(sessionId: String): ConcurrentMap<String, AgentTaskApprovalState> =
    statesBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
}
