package com.opencray.app

import com.opencray.runtime.CommandApprovalToken
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

internal enum class AgentTaskApprovalState {
  APPROVED,
  REJECTED,
  DECIDING,
}

internal data class AgentTaskApprovalGrant(
  val taskId: String,
  val toolName: String? = null,
  val promptCheckpointBoundary: com.opencray.runtime.OpenCrayPromptCheckpointBoundary? = null,
  val promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState? = null,
  val subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume? = null,
  val commandApprovalToken: CommandApprovalToken? = null,
)

internal data class AgentTaskApprovalRejection(
  val taskId: String,
  val toolName: String? = null,
  val promptCheckpointBoundary: com.opencray.runtime.OpenCrayPromptCheckpointBoundary? = null,
  val promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState? = null,
  val subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume? = null,
)

internal class AgentTaskApprovalRegistry {
  private val statesBySession: ConcurrentMap<String, ConcurrentMap<String, AgentTaskApprovalState>> =
    ConcurrentHashMap()
  private val approvedToolNamesBySession: ConcurrentMap<String, ConcurrentMap<String, String>> =
    ConcurrentHashMap()
  private val approvedPromptResumesBySession:
    ConcurrentMap<String, ConcurrentMap<String, com.opencray.runtime.OpenCrayPromptResumeState>> =
    ConcurrentHashMap()
  private val approvedSubAgentResumesBySession:
    ConcurrentMap<String, ConcurrentMap<String, com.opencray.runtime.subagent.SubAgentApprovalResume>> =
    ConcurrentHashMap()
  private val approvedCommandApprovalTokensBySession:
    ConcurrentMap<String, ConcurrentMap<String, CommandApprovalToken>> =
    ConcurrentHashMap()
  private val batchCommandApprovalTokensBySession: ConcurrentMap<String, CommandApprovalToken> =
    ConcurrentHashMap()
  private val rejectedToolNamesBySession: ConcurrentMap<String, ConcurrentMap<String, String>> =
    ConcurrentHashMap()
  private val rejectedPromptResumesBySession:
    ConcurrentMap<String, ConcurrentMap<String, com.opencray.runtime.OpenCrayPromptResumeState>> =
    ConcurrentHashMap()
  private val rejectedSubAgentResumesBySession:
    ConcurrentMap<String, ConcurrentMap<String, com.opencray.runtime.subagent.SubAgentApprovalResume>> =
    ConcurrentHashMap()

  fun markApproved(
    sessionId: String,
    taskId: String,
    toolName: String? = null,
    promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState? = null,
    subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume? = null,
    approvedRequestFingerprint: String? = null,
    commandBatchApproval: CommandBatchApprovalSpec? = null,
  ) {
    sessionState(sessionId)[taskId] = AgentTaskApprovalState.APPROVED
    rejectedToolNamesBySession[sessionId]?.remove(taskId)
    rejectedPromptResumesBySession[sessionId]?.remove(taskId)
    rejectedSubAgentResumesBySession[sessionId]?.remove(taskId)
    val toolNames = approvedToolNamesBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
    if (toolName.isNullOrBlank()) {
      toolNames.remove(taskId)
      if (toolNames.isEmpty()) {
        approvedToolNamesBySession.remove(sessionId, toolNames)
      }
    } else {
      toolNames[taskId] = toolName
    }
    val resumes = approvedPromptResumesBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
    if (promptResumeState == null) {
      resumes.remove(taskId)
      if (resumes.isEmpty()) {
        approvedPromptResumesBySession.remove(sessionId, resumes)
      }
    } else {
      resumes[taskId] = promptResumeState
    }
    val subAgentResumes = approvedSubAgentResumesBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
    if (subAgentApprovalResume == null) {
      subAgentResumes.remove(taskId)
      if (subAgentResumes.isEmpty()) {
        approvedSubAgentResumesBySession.remove(sessionId, subAgentResumes)
      }
    } else {
      subAgentResumes[taskId] = subAgentApprovalResume
    }
    val commandApprovalTokens =
      approvedCommandApprovalTokensBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
    val normalizedFingerprint = approvedRequestFingerprint
      ?.trim()
      ?.takeIf(String::isNotBlank)
    if (normalizedFingerprint == null) {
      commandApprovalTokens.remove(taskId)
      if (commandApprovalTokens.isEmpty()) {
        approvedCommandApprovalTokensBySession.remove(sessionId, commandApprovalTokens)
      }
    } else {
      commandApprovalTokens[taskId] = CommandApprovalToken(
        tokenId = UUID.randomUUID().toString(),
        taskId = taskId,
        approvedAtEpochMs = System.currentTimeMillis(),
        approvedRequestFingerprint = normalizedFingerprint,
      )
    }
    if (commandBatchApproval != null) {
      batchCommandApprovalTokensBySession[sessionId] = CommandApprovalToken(
        tokenId = UUID.randomUUID().toString(),
        taskId = taskId,
        approvedAtEpochMs = System.currentTimeMillis(),
        batchPrefixArgs = commandBatchApproval.prefixArgs,
        batchWorkingDirectory = commandBatchApproval.workingDirectory,
      )
    }
  }

  fun batchCommandApprovalToken(sessionId: String): CommandApprovalToken? =
    batchCommandApprovalTokensBySession[sessionId]

  fun markRejected(
    sessionId: String,
    taskId: String,
    toolName: String? = null,
    promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState? = null,
    subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume? = null,
  ) {
    sessionState(sessionId)[taskId] = AgentTaskApprovalState.REJECTED
    approvedToolNamesBySession[sessionId]?.remove(taskId)
    approvedPromptResumesBySession[sessionId]?.remove(taskId)
    approvedSubAgentResumesBySession[sessionId]?.remove(taskId)
    approvedCommandApprovalTokensBySession[sessionId]?.remove(taskId)
    batchCommandApprovalTokensBySession.remove(sessionId)
    val toolNames = rejectedToolNamesBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
    if (toolName.isNullOrBlank()) {
      toolNames.remove(taskId)
      if (toolNames.isEmpty()) {
        rejectedToolNamesBySession.remove(sessionId, toolNames)
      }
    } else {
      toolNames[taskId] = toolName
    }
    val resumes = rejectedPromptResumesBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
    if (promptResumeState == null) {
      resumes.remove(taskId)
      if (resumes.isEmpty()) {
        rejectedPromptResumesBySession.remove(sessionId, resumes)
      }
    } else {
      resumes[taskId] = promptResumeState
    }
    val subAgentResumes = rejectedSubAgentResumesBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
    if (subAgentApprovalResume == null) {
      subAgentResumes.remove(taskId)
      if (subAgentResumes.isEmpty()) {
        rejectedSubAgentResumesBySession.remove(sessionId, subAgentResumes)
      }
    } else {
      subAgentResumes[taskId] = subAgentApprovalResume
    }
  }

  fun consumeApproved(sessionId: String, taskId: String): AgentTaskApprovalGrant? {
    val sessionState = statesBySession[sessionId] ?: return null
    if (sessionState[taskId] != AgentTaskApprovalState.APPROVED) {
      return null
    }
    sessionState.remove(taskId)
    val toolNames = approvedToolNamesBySession[sessionId]
    val resumes = approvedPromptResumesBySession[sessionId]
    val subAgentResumes = approvedSubAgentResumesBySession[sessionId]
    val commandApprovalTokens = approvedCommandApprovalTokensBySession[sessionId]
    val toolName = toolNames?.remove(taskId)
    val promptResumeState = resumes?.remove(taskId)
    val subAgentApprovalResume = subAgentResumes?.remove(taskId)
    val commandApprovalToken = commandApprovalTokens?.remove(taskId)
    if (sessionState.isEmpty()) {
      statesBySession.remove(sessionId, sessionState)
    }
    if (toolNames != null && toolNames.isEmpty()) {
      approvedToolNamesBySession.remove(sessionId, toolNames)
    }
    if (resumes != null && resumes.isEmpty()) {
      approvedPromptResumesBySession.remove(sessionId, resumes)
    }
    if (subAgentResumes != null && subAgentResumes.isEmpty()) {
      approvedSubAgentResumesBySession.remove(sessionId, subAgentResumes)
    }
    if (commandApprovalTokens != null && commandApprovalTokens.isEmpty()) {
      approvedCommandApprovalTokensBySession.remove(sessionId, commandApprovalTokens)
    }
    return AgentTaskApprovalGrant(
      taskId = taskId,
      toolName = toolName,
      promptResumeState = promptResumeState,
      subAgentApprovalResume = subAgentApprovalResume,
      commandApprovalToken = commandApprovalToken,
    )
  }

  fun consumeRejected(sessionId: String, taskId: String): AgentTaskApprovalRejection? {
    val sessionState = statesBySession[sessionId] ?: return null
    if (sessionState[taskId] != AgentTaskApprovalState.REJECTED) {
      return null
    }
    sessionState.remove(taskId)
    val toolNames = rejectedToolNamesBySession[sessionId]
    val resumes = rejectedPromptResumesBySession[sessionId]
    val subAgentResumes = rejectedSubAgentResumesBySession[sessionId]
    val toolName = toolNames?.remove(taskId)
    val promptResumeState = resumes?.remove(taskId)
    val subAgentApprovalResume = subAgentResumes?.remove(taskId)
    if (sessionState.isEmpty()) {
      statesBySession.remove(sessionId, sessionState)
    }
    if (toolNames != null && toolNames.isEmpty()) {
      rejectedToolNamesBySession.remove(sessionId, toolNames)
    }
    if (resumes != null && resumes.isEmpty()) {
      rejectedPromptResumesBySession.remove(sessionId, resumes)
    }
    if (subAgentResumes != null && subAgentResumes.isEmpty()) {
      rejectedSubAgentResumesBySession.remove(sessionId, subAgentResumes)
    }
    return AgentTaskApprovalRejection(
      taskId = taskId,
      toolName = toolName,
      promptResumeState = promptResumeState,
      subAgentApprovalResume = subAgentApprovalResume,
    )
  }

  fun isRejected(sessionId: String, taskId: String): Boolean {
    val sessionState = statesBySession[sessionId] ?: return false
    return sessionState[taskId] == AgentTaskApprovalState.REJECTED
  }

  fun tryBeginDecision(sessionId: String, taskId: String): Boolean {
    val sessionState = statesBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
    return sessionState.putIfAbsent(taskId, AgentTaskApprovalState.DECIDING) == null
  }

  fun releaseUnresolvedDecision(sessionId: String, taskId: String) {
    statesBySession[sessionId]?.remove(taskId, AgentTaskApprovalState.DECIDING)
  }

  fun isApproved(sessionId: String, taskId: String): Boolean {
    val sessionState = statesBySession[sessionId] ?: return false
    return sessionState[taskId] == AgentTaskApprovalState.APPROVED
  }

  fun clear(sessionId: String, taskId: String) {
    statesBySession[sessionId]?.remove(taskId)
    approvedToolNamesBySession[sessionId]?.remove(taskId)
    approvedPromptResumesBySession[sessionId]?.remove(taskId)
    approvedSubAgentResumesBySession[sessionId]?.remove(taskId)
    approvedCommandApprovalTokensBySession[sessionId]?.remove(taskId)
    batchCommandApprovalTokensBySession.remove(sessionId)
    rejectedToolNamesBySession[sessionId]?.remove(taskId)
    rejectedPromptResumesBySession[sessionId]?.remove(taskId)
    rejectedSubAgentResumesBySession[sessionId]?.remove(taskId)
  }

  fun retainKnownTasks(sessionId: String, taskIds: Set<String>) {
    val sessionState = statesBySession[sessionId] ?: return
    if (taskIds.isEmpty()) {
      batchCommandApprovalTokensBySession.remove(sessionId)
    }
    sessionState.keys.retainAll(taskIds)
    approvedToolNamesBySession[sessionId]?.let { toolNames ->
      toolNames.keys.retainAll(taskIds)
      if (toolNames.isEmpty()) {
        approvedToolNamesBySession.remove(sessionId, toolNames)
      }
    }
    approvedPromptResumesBySession[sessionId]?.let { resumes ->
      resumes.keys.retainAll(taskIds)
      if (resumes.isEmpty()) {
        approvedPromptResumesBySession.remove(sessionId, resumes)
      }
    }
    approvedSubAgentResumesBySession[sessionId]?.let { resumes ->
      resumes.keys.retainAll(taskIds)
      if (resumes.isEmpty()) {
        approvedSubAgentResumesBySession.remove(sessionId, resumes)
      }
    }
    approvedCommandApprovalTokensBySession[sessionId]?.let { tokens ->
      tokens.keys.retainAll(taskIds)
      if (tokens.isEmpty()) {
        approvedCommandApprovalTokensBySession.remove(sessionId, tokens)
      }
    }
    rejectedToolNamesBySession[sessionId]?.let { toolNames ->
      toolNames.keys.retainAll(taskIds)
      if (toolNames.isEmpty()) {
        rejectedToolNamesBySession.remove(sessionId, toolNames)
      }
    }
    rejectedPromptResumesBySession[sessionId]?.let { resumes ->
      resumes.keys.retainAll(taskIds)
      if (resumes.isEmpty()) {
        rejectedPromptResumesBySession.remove(sessionId, resumes)
      }
    }
    rejectedSubAgentResumesBySession[sessionId]?.let { resumes ->
      resumes.keys.retainAll(taskIds)
      if (resumes.isEmpty()) {
        rejectedSubAgentResumesBySession.remove(sessionId, resumes)
      }
    }
    if (sessionState.isEmpty()) {
      statesBySession.remove(sessionId, sessionState)
    }
  }

  private fun sessionState(sessionId: String): ConcurrentMap<String, AgentTaskApprovalState> =
    statesBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
}
