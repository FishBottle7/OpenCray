package com.opencray.app

internal class ChatPendingApprovalState {
  private val lock = Any()
  private val approvalsBySession = linkedMapOf<String, LinkedHashMap<String, PendingApprovalSnapshot>>()

  fun hasApprovals(sessionId: String): Boolean = synchronized(lock) {
    approvalsBySession[sessionId]?.isNotEmpty() == true
  }

  fun approvalsForSession(sessionId: String): Map<String, PendingApprovalSnapshot> = synchronized(lock) {
    approvalsBySession[sessionId]?.toMap().orEmpty()
  }

  fun put(
    sessionId: String,
    taskId: String,
    approval: PendingApprovalSnapshot,
  ) {
    synchronized(lock) {
      approvalsBySession.getOrPut(sessionId) { linkedMapOf() }[taskId] = approval
    }
  }

  fun remove(
    sessionId: String,
    taskId: String,
  ): PendingApprovalSnapshot? = synchronized(lock) {
    val sessionApprovals = approvalsBySession[sessionId] ?: return@synchronized null
    val removed = sessionApprovals.remove(taskId)
    if (sessionApprovals.isEmpty()) {
      approvalsBySession.remove(sessionId)
    }
    removed
  }

  fun removeSession(sessionId: String): List<PendingApprovalSnapshot> = synchronized(lock) {
    approvalsBySession.remove(sessionId)?.values?.toList().orEmpty()
  }

  fun removeByPendingMessageIds(
    sessionId: String,
    pendingMessageIds: Set<String>,
  ): List<PendingApprovalSnapshot> = synchronized(lock) {
    if (pendingMessageIds.isEmpty()) {
      return@synchronized emptyList()
    }
    val sessionApprovals = approvalsBySession[sessionId] ?: return@synchronized emptyList()
    val removed = mutableListOf<PendingApprovalSnapshot>()
    val iterator = sessionApprovals.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (entry.value.pendingMessageId !in pendingMessageIds) {
        continue
      }
      removed += entry.value
      iterator.remove()
    }
    if (sessionApprovals.isEmpty()) {
      approvalsBySession.remove(sessionId)
    }
    removed
  }
}
