package com.opencray.app

internal fun knownChatSessionIds(
  chatSessionStore: ChatSessionLocalStore,
): List<String> {
  val state = chatSessionStore.loadState()
  return buildList {
    add(state.activeSession.sessionId)
    addAll(state.sessions.map(ChatSessionLocalStore.SessionSummary::sessionId))
  }.distinct()
}

internal fun findChatRunSnapshotForIdentifier(
  chatSessionStore: ChatSessionLocalStore,
  runtimeHostAccess: RuntimeSessionDirectoryAccess,
  runIdOrTaskId: String,
): AgentRunSnapshot? = findChatRunSnapshotForIdentifier(
  sessionIds = knownChatSessionIds(chatSessionStore),
  runtimeHostAccess = runtimeHostAccess,
  runIdOrTaskId = runIdOrTaskId,
)

internal fun findChatRunSnapshotForIdentifier(
  sessionIds: List<String>,
  runtimeHostAccess: RuntimeSessionDirectoryAccess,
  runIdOrTaskId: String,
): AgentRunSnapshot? {
  sessionIds.firstNotNullOfOrNull { sessionId ->
    runtimeHostAccess.session(sessionId).findRun(runIdOrTaskId)
  }?.let { run ->
    return run
  }
  return sessionIds.firstNotNullOfOrNull { sessionId ->
    runtimeHostAccess.session(sessionId)
      .listRuns()
      .firstOrNull { run -> run.taskId == runIdOrTaskId }
  }
}
