package com.opencray.app

import com.opencray.runtime.OpenCrayAgentRunEvent

internal class ChatRuntimeEventState {
  private val lock = Any()
  private val eventsBySession = linkedMapOf<String, ArrayDeque<OpenCrayAgentRunEvent>>()

  fun hasEvents(sessionId: String): Boolean = synchronized(lock) {
    eventsBySession[sessionId]?.isNotEmpty() == true
  }

  fun eventsForSession(sessionId: String): List<OpenCrayAgentRunEvent> = synchronized(lock) {
    eventsBySession[sessionId]?.toList().orEmpty()
  }

  fun snapshotBySession(): Map<String, List<OpenCrayAgentRunEvent>> = synchronized(lock) {
    eventsBySession.mapValues { (_, events) -> events.toList() }
  }

  fun append(
    sessionId: String,
    event: OpenCrayAgentRunEvent,
    maxHistory: Int,
  ) {
    synchronized(lock) {
      val events = eventsBySession.getOrPut(sessionId) { ArrayDeque() }
      events += event
      while (events.size > maxHistory) {
        events.removeFirst()
      }
    }
  }

  fun removeSession(sessionId: String) {
    synchronized(lock) {
      eventsBySession.remove(sessionId)
    }
  }
}
