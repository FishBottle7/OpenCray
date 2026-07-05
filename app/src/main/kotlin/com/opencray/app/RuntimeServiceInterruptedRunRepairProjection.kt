package com.opencray.app

internal data class RuntimeServiceInterruptedRunRepairProjection(
  val scannedSessionIds: List<String> = emptyList(),
  val resumedSessionIds: List<String> = emptyList(),
  val repairedSessionIds: List<String> = emptyList(),
  val repairEvidenceBySession: Map<String, List<InterruptedRunRepairEvidence>> = emptyMap(),
  val nextRepairAfterEpochMs: Long? = null,
  val nextRepairReason: String? = null,
  val recordedAtEpochMs: Long = System.currentTimeMillis(),
) {
  fun snapshotMap(): Map<String, Any?> = buildMap {
    put("recordedAtEpochMs", recordedAtEpochMs)
    put("scannedSessionIds", scannedSessionIds)
    put("resumedSessionIds", resumedSessionIds)
    put("repairedSessionIds", repairedSessionIds)
    nextRepairAfterEpochMs?.let { put("nextRepairAfterEpochMs", it) }
    nextRepairReason?.let { put("nextRepairReason", it) }
    put(
      "repairEvidenceBySession",
      repairEvidenceBySession.mapValues { entry ->
        entry.value.map { evidence -> evidence.snapshotMap() }
      },
    )
  }
}

internal fun RuntimeServiceBootstrapResult.toInterruptedRunRepairProjection(
  recordedAtEpochMs: Long = System.currentTimeMillis(),
): RuntimeServiceInterruptedRunRepairProjection =
  RuntimeServiceInterruptedRunRepairProjection(
    scannedSessionIds = scannedSessionIds,
    resumedSessionIds = resumedSessionIds,
    repairedSessionIds = repairedSessionIds,
    repairEvidenceBySession = repairEvidenceBySession,
    nextRepairAfterEpochMs = nextRepairAfterEpochMs,
    nextRepairReason = nextRepairReason,
    recordedAtEpochMs = recordedAtEpochMs,
  )

internal fun RuntimeServiceInterruptedRunRepairResult.toInterruptedRunRepairProjection(
  recordedAtEpochMs: Long = System.currentTimeMillis(),
): RuntimeServiceInterruptedRunRepairProjection =
  RuntimeServiceInterruptedRunRepairProjection(
    scannedSessionIds = scannedSessionIds,
    resumedSessionIds = resumedSessionIds,
    repairedSessionIds = repairedSessionIds,
    repairEvidenceBySession = repairEvidenceBySession,
    nextRepairAfterEpochMs = nextRepairAfterEpochMs,
    nextRepairReason = nextRepairReason,
    recordedAtEpochMs = recordedAtEpochMs,
  )

internal fun InterruptedRunRepairEvidence.snapshotMap(): Map<String, Any?> = buildMap {
  put("sessionId", sessionId)
  put("kind", kind.wireValue)
  put("target", target.wireValue)
  runId?.let { put("runId", it) }
  taskId?.let { put("taskId", it) }
  detailId?.let { put("detailId", it) }
  repairAfterEpochMs?.let { put("repairAfterEpochMs", it) }
  managedProcessReconnectStatus?.let { put("managedProcessReconnectStatus", it) }
  managedProcessReconnectRecoveryState?.let { put("managedProcessReconnectRecoveryState", it) }
  managedProcessReconnectAttemptCount?.let { put("managedProcessReconnectAttemptCount", it) }
  runtimeExecutionOwnershipTier?.let { put("runtimeExecutionOwnershipTier", it) }
  durableRuntimeControllerId?.let { put("durableRuntimeControllerId", it) }
  managedProcessContinuationBasis?.let { put("managedProcessContinuationBasis", it) }
  managedProcessRestoreScope?.let { put("managedProcessRestoreScope", it) }
  managedProcessRestoreDecision?.let { put("managedProcessRestoreDecision", it) }
}

internal val InterruptedRunRepairEvidenceKind.wireValue: String
  get() = when (this) {
    InterruptedRunRepairEvidenceKind.QUEUE_TASK -> "queue_task"
    InterruptedRunRepairEvidenceKind.PROMPT_CHECKPOINT -> "prompt_checkpoint"
    InterruptedRunRepairEvidenceKind.DETACHED_SUBAGENT_HANDLE -> "detached_subagent_handle"
    InterruptedRunRepairEvidenceKind.RUN_RECORD -> "run_record"
    InterruptedRunRepairEvidenceKind.JOURNAL_TAIL -> "journal_tail"
    InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT -> "managed_process_reconnect"
  }

internal fun interruptedRunRepairEvidenceKindFromWireValue(
  rawValue: String?,
): InterruptedRunRepairEvidenceKind? {
  val normalizedValue = rawValue
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return null
  return InterruptedRunRepairEvidenceKind.entries.firstOrNull { kind ->
    kind.wireValue.equals(normalizedValue, ignoreCase = true) ||
      kind.name.equals(normalizedValue, ignoreCase = true)
  }
}
