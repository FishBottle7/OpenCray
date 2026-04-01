package com.opencray.runtime.workingstate

import java.util.concurrent.atomic.AtomicReference

interface WorkingStateStore {
  fun snapshot(): WorkingState

  fun replace(state: WorkingState)
}

class InMemoryWorkingStateStore(
  initialState: WorkingState = WorkingState(),
) : WorkingStateStore {
  private val stateRef: AtomicReference<WorkingState> = AtomicReference(initialState)

  override fun snapshot(): WorkingState = stateRef.get()

  override fun replace(state: WorkingState) {
    stateRef.set(state)
  }
}
