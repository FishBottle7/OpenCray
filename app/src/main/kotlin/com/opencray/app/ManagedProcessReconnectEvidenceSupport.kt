package com.opencray.app

import com.opencray.runtime.process.ManagedProcessSnapshot

internal data class ManagedProcessReconnectSnapshotEvidence(
  val status: String? = null,
  val recoveryState: String? = null,
  val retryable: Boolean? = null,
  val retryAfterEpochMs: Long? = null,
  val attemptCount: Int? = null,
) {
  val hasEvidence: Boolean
    get() = status != null ||
      recoveryState != null ||
      retryable != null ||
      retryAfterEpochMs != null ||
      attemptCount != null

  fun mergedWith(fallback: ManagedProcessReconnectSnapshotEvidence): ManagedProcessReconnectSnapshotEvidence =
    copy(
      status = status ?: fallback.status,
      recoveryState = recoveryState ?: fallback.recoveryState,
      retryable = retryable ?: fallback.retryable,
      retryAfterEpochMs = retryAfterEpochMs ?: fallback.retryAfterEpochMs,
      attemptCount = attemptCount ?: fallback.attemptCount,
    )
}

internal fun ManagedProcessSnapshot.reconnectSnapshotEvidence(): ManagedProcessReconnectSnapshotEvidence {
  val typedEvidence = reconnectState?.let { reconnect ->
    ManagedProcessReconnectSnapshotEvidence(
      status = reconnect.status.normalizedReconnectString(),
      recoveryState = reconnect.recoveryState.normalizedReconnectString(),
      retryable = reconnect.retryable,
      retryAfterEpochMs = reconnect.retryAfterEpochMs,
      attemptCount = reconnect.attemptCount,
    )
  } ?: ManagedProcessReconnectSnapshotEvidence()
  val metadataEvidence = ManagedProcessReconnectSnapshotEvidence(
    status = metadata["sandboxCommandReconnectStatus"].normalizedReconnectString(),
    recoveryState = metadata["sandboxCommandReconnectRecoveryState"].normalizedReconnectString(),
    retryable = metadata["sandboxCommandReconnectRetryable"]
      ?.trim()
      ?.lowercase()
      ?.toBooleanStrictOrNull(),
    retryAfterEpochMs = metadata["sandboxCommandReconnectRetryAfterEpochMs"]
      ?.trim()
      ?.toLongOrNull(),
    attemptCount = metadata["sandboxCommandReconnectAttemptCount"]
      ?.trim()
      ?.toIntOrNull(),
  )
  return if (metadataEvidence.isNewerThan(typedEvidence)) {
    if (metadataEvidence.isStableReconnectEvidence() && typedEvidence.isRetryPendingEvidence()) {
      metadataEvidence
    } else {
      metadataEvidence.mergedWith(typedEvidence)
    }
  } else {
    typedEvidence.mergedWith(metadataEvidence)
  }
}

private fun ManagedProcessReconnectSnapshotEvidence.isNewerThan(
  typedEvidence: ManagedProcessReconnectSnapshotEvidence,
): Boolean {
  if (!hasEvidence) {
    return false
  }
  if (!typedEvidence.hasEvidence) {
    return true
  }
  val metadataAttemptCount = attemptCount
  val typedAttemptCount = typedEvidence.attemptCount
  if (metadataAttemptCount != null && typedAttemptCount != null) {
    if (metadataAttemptCount > typedAttemptCount) {
      return true
    }
    if (metadataAttemptCount < typedAttemptCount) {
      return false
    }
  } else if (metadataAttemptCount != null) {
    return true
  }
  return isStableReconnectEvidence() && typedEvidence.isRetryPendingEvidence()
}

private fun ManagedProcessReconnectSnapshotEvidence.isStableReconnectEvidence(): Boolean =
  retryable == false ||
    recoveryState in STABLE_RECONNECT_RECOVERY_STATES ||
    status in STABLE_RECONNECT_STATUSES

private fun ManagedProcessReconnectSnapshotEvidence.isRetryPendingEvidence(): Boolean =
  retryable == true ||
    recoveryState in RETRY_PENDING_RECONNECT_RECOVERY_STATES ||
    status in RETRY_PENDING_RECONNECT_STATUSES

private fun String?.normalizedReconnectString(): String? =
  this
    ?.trim()
    ?.lowercase()
    ?.takeIf(String::isNotBlank)

private val STABLE_RECONNECT_RECOVERY_STATES = setOf(
  "attached_live",
  "completed",
)

private val STABLE_RECONNECT_STATUSES = setOf(
  "attached",
  "completed",
)

private val RETRY_PENDING_RECONNECT_RECOVERY_STATES = setOf(
  "retry_scheduled",
)

private val RETRY_PENDING_RECONNECT_STATUSES = setOf(
  "connecting",
)
