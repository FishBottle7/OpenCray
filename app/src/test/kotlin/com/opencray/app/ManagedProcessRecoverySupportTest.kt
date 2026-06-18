package com.opencray.app

import com.opencray.runtime.process.ManagedProcessReconnectState
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedProcessRecoverySupportTest {
  @Test
  fun metadataAttachedLiveOverridesStaleTypedRetryForAutoResumeEligibility() {
    val snapshot = runningManagedProcess(
      reconnectState = ManagedProcessReconnectState(
        status = "connecting",
        recoveryState = "retry_scheduled",
        retryable = true,
        retryAfterEpochMs = 9_000L,
        attemptCount = 1,
      ),
      metadata = mapOf(
        "sandboxCommandReconnectStatus" to "attached",
        "sandboxCommandReconnectRecoveryState" to "attached_live",
        "sandboxCommandReconnectRetryable" to "false",
        "sandboxCommandReconnectAttemptCount" to "2",
      ),
    )

    assertTrue(snapshot.isAutoResumeEligibleManagedProcess())
  }

  @Test
  fun retryScheduledMetadataRemainsNotAutoResumeEligible() {
    val snapshot = runningManagedProcess(
      metadata = mapOf(
        "sandboxCommandReconnectStatus" to "connecting",
        "sandboxCommandReconnectRecoveryState" to "retry_scheduled",
        "sandboxCommandReconnectRetryable" to "true",
        "sandboxCommandReconnectRetryAfterEpochMs" to "9_000",
      ),
    )

    assertFalse(snapshot.isAutoResumeEligibleManagedProcess())
  }

  private fun runningManagedProcess(
    reconnectState: ManagedProcessReconnectState? = null,
    metadata: Map<String, String> = emptyMap(),
  ): ManagedProcessSnapshot = ManagedProcessSnapshot(
    processId = "process-1",
    taskId = "task-1",
    command = "python",
    status = ManagedProcessStatus.RUNNING,
    processStarted = true,
    timeoutMs = 30_000L,
    startedAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_100L,
    reconnectState = reconnectState,
    metadata = metadata,
  )
}
