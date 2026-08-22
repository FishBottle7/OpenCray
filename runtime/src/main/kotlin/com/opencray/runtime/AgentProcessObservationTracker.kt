package com.opencray.runtime

import com.opencray.runtime.process.ManagedProcessObservationCursorState

class ManagedProcessObservationTracker(
  private val maxTrackedProcesses: Int = 64,
) {
  private val lock = Any()
  private val statesByProcessId = linkedMapOf<String, ManagedProcessObservationCursorState>()

  fun recordAndReturnPrevious(
    processId: String,
    current: ManagedProcessObservationCursorState,
  ): ManagedProcessObservationCursorState? = synchronized(lock) {
    val previous = statesByProcessId.put(processId, current)
    while (statesByProcessId.size > maxTrackedProcesses) {
      val iterator = statesByProcessId.entries.iterator()
      if (!iterator.hasNext()) {
        break
      }
      iterator.next()
      iterator.remove()
    }
    previous
  }
}
