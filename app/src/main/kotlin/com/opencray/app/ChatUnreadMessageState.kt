package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry

internal class ChatUnreadMessageState {
  private val lock = Any()
  private val unreadCountsBySession = linkedMapOf<String, Int>()

  fun incrementIfBackgroundUpdate(
    sessionId: String,
    activeSessionId: String,
    text: String?,
    attachments: List<ChatAttachmentEntry> = emptyList(),
  ) {
    if (sessionId == activeSessionId) {
      return
    }
    val normalized = text?.trim().orEmpty()
    if (normalized.isEmpty() && attachments.isEmpty()) {
      return
    }
    synchronized(lock) {
      unreadCountsBySession[sessionId] = (unreadCountsBySession[sessionId] ?: 0) + 1
    }
  }

  fun clear(sessionId: String) {
    synchronized(lock) {
      unreadCountsBySession.remove(sessionId)
    }
  }

  fun rawCount(sessionId: String): Int = synchronized(lock) {
    unreadCountsBySession[sessionId] ?: 0
  }

  fun countForSession(
    sessionId: String,
    activeSessionId: String,
  ): Int = if (sessionId == activeSessionId) {
    0
  } else {
    rawCount(sessionId)
  }
}
