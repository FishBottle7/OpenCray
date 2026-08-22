package com.opencray.runtime.media

import com.opencray.runtime.AgentToolResult
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class MediaJobHandle(
  val jobId: String,
  val toolName: String,
  val summary: String,
  val createdAtEpochMs: Long,
  val cancelRequested: AtomicBoolean,
  val future: Future<AgentToolResult>,
  val baseMetadata: Map<String, String>,
)

internal class MediaJobCoordinator {
  val executor = Executors.newCachedThreadPool { runnable ->
    Thread(runnable).apply {
      name = "OpenCrayMediaJob"
      isDaemon = true
    }
  }
  val idCounter = AtomicLong(0L)
  val jobs = linkedMapOf<String, MediaJobHandle>()
}
