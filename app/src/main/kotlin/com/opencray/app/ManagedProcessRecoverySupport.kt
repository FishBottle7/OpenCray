package com.opencray.app

import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus

internal fun ManagedProcessSnapshot.isAutoResumeEligibleManagedProcess(): Boolean {
  if (status != ManagedProcessStatus.RUNNING) {
    return false
  }
  val reconnectEvidence = reconnectSnapshotEvidence()
  if (!reconnectEvidence.hasEvidence && !hasReconnectMetadata()) {
    return true
  }
  return reconnectEvidence.recoveryState == MANAGED_PROCESS_RECOVERY_STATE_ATTACHED_LIVE ||
    reconnectEvidence.recoveryState == MANAGED_PROCESS_RECOVERY_STATE_COMPLETED
}

private fun ManagedProcessSnapshot.hasReconnectMetadata(): Boolean =
  reconnectState != null ||
    metadata.keys.any { key -> key.startsWith("sandboxCommandReconnect") }

private const val MANAGED_PROCESS_RECOVERY_STATE_ATTACHED_LIVE: String = "attached_live"
private const val MANAGED_PROCESS_RECOVERY_STATE_COMPLETED: String = "completed"
