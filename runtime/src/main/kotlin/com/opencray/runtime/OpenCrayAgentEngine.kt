package com.opencray.runtime

import com.opencray.core.orchestrator.AgentLoop
import com.opencray.core.orchestrator.NoOpSessionQueueRestoreTransformer
import com.opencray.core.orchestrator.QueueClock
import com.opencray.core.orchestrator.SessionQueue
import com.opencray.core.orchestrator.SessionQueueConfig
import com.opencray.core.orchestrator.SessionQueueRestoreTransformer
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.core.orchestrator.SystemQueueClock

class OpenCrayAgentEngine(
  private val runtime: SessionTaskRuntime,
  private val clock: QueueClock = SystemQueueClock,
  private val restoreTransformer: SessionQueueRestoreTransformer = NoOpSessionQueueRestoreTransformer,
  private val queueConfig: SessionQueueConfig = SessionQueueConfig(),
) {
  fun create(
    sessionId: String,
    agentId: String,
    snapshotStore: SessionQueueSnapshotStore,
  ): AgentLoop = AgentLoop(
    queue = SessionQueue(
      sessionId = sessionId,
      agentId = agentId,
      runtime = runtime,
      snapshotStore = snapshotStore,
      restoreTransformer = restoreTransformer,
      clock = clock,
      config = queueConfig,
    ),
  )
}
