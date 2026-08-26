package com.opencray.runtime

import com.opencray.core.contracts.AgentTask

object DispatchTaskScope {
  private val currentTask = ThreadLocal<AgentTask?>()

  fun currentTask(): AgentTask? = currentTask.get()

  fun <T> withCurrentTask(
    task: AgentTask,
    block: () -> T,
  ): T {
    val previous = currentTask.get()
    currentTask.set(task)
    return try {
      block()
    } finally {
      if (previous == null) {
        currentTask.remove()
      } else {
        currentTask.set(previous)
      }
    }
  }
}
