package com.opencray.app

import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStateStore

internal class ChatSessionBackedWorkingStateStore(
  private val chatSessionStore: ChatSessionLocalStore,
  private val sessionId: String,
) : WorkingStateStore {
  override fun snapshot(): WorkingState = chatSessionStore.loadWorkingState(sessionId)

  override fun replace(state: WorkingState) {
    chatSessionStore.replaceWorkingState(
      sessionId = sessionId,
      workingState = state,
    )
  }
}
