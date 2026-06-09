package com.opencray.runtime.session

import com.opencray.runtime.context.RuntimeConversationMessage

data class SessionSearchToolContext(
  val sessionId: String,
  val sessions: List<SessionSearchSession> = emptyList(),
) {
  init {
    require(sessionId.isNotBlank()) { "SessionSearchToolContext sessionId must not be blank." }
  }
}

data class SessionSearchSession(
  val sessionId: String,
  val title: String? = null,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val messages: List<RuntimeConversationMessage> = emptyList(),
  val compactionSummaries: List<SessionSearchCompactionSummary> = emptyList(),
) {
  init {
    require(sessionId.isNotBlank()) { "SessionSearchSession sessionId must not be blank." }
    require(title == null || title.isNotBlank()) { "SessionSearchSession title must not be blank when provided." }
    require(createdAtEpochMs >= 0L) { "SessionSearchSession createdAtEpochMs must be >= 0." }
    require(updatedAtEpochMs >= 0L) { "SessionSearchSession updatedAtEpochMs must be >= 0." }
  }
}

data class SessionSearchCompactionSummary(
  val text: String,
  val compactedAtEpochMs: Long? = null,
) {
  init {
    require(text.isNotBlank()) { "SessionSearchCompactionSummary text must not be blank." }
    require(compactedAtEpochMs == null || compactedAtEpochMs >= 0L) {
      "SessionSearchCompactionSummary compactedAtEpochMs must be >= 0 when provided."
    }
  }
}
