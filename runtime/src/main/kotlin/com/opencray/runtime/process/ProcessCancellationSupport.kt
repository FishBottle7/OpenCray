package com.opencray.runtime.process

import java.util.concurrent.ConcurrentHashMap

fun interface ManagedProcessCancellationCheck {
  fun isCancellationRequested(): Boolean
}

object ManagedProcessCancellationRegistry {
  private val checksByTaskId = ConcurrentHashMap<String, ManagedProcessCancellationCheck>()

  fun register(
    taskId: String,
    check: ManagedProcessCancellationCheck,
  ): AutoCloseable {
    checksByTaskId[taskId] = check
    return AutoCloseable { unregister(taskId = taskId, check = check) }
  }

  fun unregister(
    taskId: String,
    check: ManagedProcessCancellationCheck,
  ) {
    checksByTaskId.remove(taskId, check)
  }

  fun checkFor(taskId: String): ManagedProcessCancellationCheck? = checksByTaskId[taskId]

  fun clearForTest() {
    checksByTaskId.clear()
  }
}

const val MAX_MANAGED_PROCESS_WAIT_TIMEOUT_MS: Long = 300_000L
const val MAX_MANAGED_PROCESS_TIMEOUT_MS: Long = 1_800_000L
