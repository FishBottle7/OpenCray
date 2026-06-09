package com.opencray.app

import com.opencray.runtime.process.ManagedProcessReconnectState
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.process.withNormalizedRemoteState

internal fun ManagedProcessSnapshot.isAutoResumeEligibleManagedProcess(): Boolean {
  if (status != ManagedProcessStatus.RUNNING) {
    return false
  }
  val normalizedSnapshot = withNormalizedRemoteState()
  val reconnectState = normalizedSnapshot.reconnectState
  if (reconnectState == null && !normalizedSnapshot.hasReconnectMetadata()) {
    return true
  }
  val recoveryState = reconnectState.normalizedRecoveryState()
    ?: normalizedSnapshot.metadata["sandboxCommandReconnectRecoveryState"]
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotBlank)
  return recoveryState == MANAGED_PROCESS_RECOVERY_STATE_ATTACHED_LIVE ||
    recoveryState == MANAGED_PROCESS_RECOVERY_STATE_COMPLETED
}

private fun ManagedProcessReconnectState?.normalizedRecoveryState(): String? =
  this?.recoveryState
    ?.trim()
    ?.lowercase()
    ?.takeIf(String::isNotBlank)

private fun ManagedProcessSnapshot.hasReconnectMetadata(): Boolean =
  reconnectState != null ||
    metadata.keys.any { key -> key.startsWith("sandboxCommandReconnect") }

private const val MANAGED_PROCESS_RECOVERY_STATE_ATTACHED_LIVE: String = "attached_live"
private const val MANAGED_PROCESS_RECOVERY_STATE_COMPLETED: String = "completed"
