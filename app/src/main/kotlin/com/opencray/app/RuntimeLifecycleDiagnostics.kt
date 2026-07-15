package com.opencray.app

import com.opencray.core.orchestrator.METADATA_CHECKPOINT_RESUME_ATTEMPT_COUNT
import com.opencray.core.orchestrator.METADATA_PREVIOUS_LIFECYCLE_STATE
import com.opencray.core.orchestrator.METADATA_QUEUE_RESTORE_EPOCH_MS
import com.opencray.core.orchestrator.METADATA_RECOVERY_REASON
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY
import com.opencray.runtime.process.ManagedProcessRestoreScope
import java.util.UUID

internal object RunLifecycleMetadataKeys {
  const val PROCESS_START_ID: String = "_host.processStartId"
  const val HOST_INSTANCE_ID: String = "_host.hostInstanceId"
  const val RUNTIME_OWNER_ID: String = "_host.runtimeOwnerId"
  const val RUNTIME_CONTROLLER_ID: String = "_host.runtimeControllerId"
  const val DURABLE_RUNTIME_CONTROLLER_ID: String = "_host.durableRuntimeControllerId"
  const val RUNTIME_EXECUTION_OWNERSHIP_TIER: String = "_host.runtimeExecutionOwnershipTier"
  const val RUNTIME_CONTROLLER_PROCESS_SEPARATE: String =
    "_host.runtimeControllerProcessSeparate"
  const val RUN_ATTEMPT: String = "_host.runAttempt"
  const val CHECKPOINT_RESUME_ATTEMPT_COUNT: String =
    METADATA_CHECKPOINT_RESUME_ATTEMPT_COUNT
  const val RECOVERED_FROM_CHECKPOINT_ID: String = "_host.recoveredFromCheckpointId"
  const val RECOVERY_ACTION: String = "_host.recoveryAction"
  const val MANAGED_PROCESS_RECONNECT_PROCESS_IDS: String =
    "_host.managedProcessReconnectProcessIds"
  const val MANAGED_PROCESS_RECONNECT_STATUS: String =
    "_host.managedProcessReconnectStatus"
  const val MANAGED_PROCESS_RECONNECT_RECOVERY_STATE: String =
    "_host.managedProcessReconnectRecoveryState"
  const val MANAGED_PROCESS_RECONNECT_RETRY_AFTER_EPOCH_MS: String =
    "_host.managedProcessReconnectRetryAfterEpochMs"
  const val MANAGED_PROCESS_RECONNECT_ATTEMPT_COUNT: String =
    "_host.managedProcessReconnectAttemptCount"
  const val MANAGED_PROCESS_CONTINUATION_BASIS: String =
    "_host.managedProcessContinuationBasis"
  const val MANAGED_PROCESS_RESTORE_SCOPE: String =
    "_host.managedProcessRestoreScope"
  const val MANAGED_PROCESS_RESTORE_DECISION: String =
    "_host.managedProcessRestoreDecision"
  const val SUBMISSION_SOURCE: String = "_host.submissionSource"
  const val PREAPPROVED_TOOL_NAME: String = "_host.preapprovedToolName"
}

internal object RuntimeExecutionOwnershipTiers {
  const val RUNTIME_PROCESS: String = "runtime_process"
}

internal object ManagedProcessContinuationBases {
  const val CHECKPOINT_RESUME: String = "checkpoint_resume"
  const val LIVE_REATTACH: String = "live_reattach"
  const val RECONNECT_HOLD: String = "reconnect_hold"
}

internal object RunLifecycleRecoveryReasons {
  const val MANAGED_PROCESS_RESTORE_INTERRUPTED: String = "managed_process_restore_interrupted"
  const val MANAGED_PROCESS_RESTORE_INTERRUPTED_SAME_CONTROLLER: String =
    "managed_process_restore_interrupted_same_controller"
  const val MANAGED_PROCESS_RESTORE_INTERRUPTED_SAME_PROCESS_NEW_CONTROLLER: String =
    "managed_process_restore_interrupted_same_process_new_controller"
  const val MANAGED_PROCESS_RESTORE_INTERRUPTED_CROSS_PROCESS: String =
    "managed_process_restore_interrupted_cross_process"
}

internal object RunSubmissionSources {
  const val CHAT_USER_MESSAGE: String = "chat_user_message"
  const val CHAT_QUEUED_FOLLOW_UP: String = "chat_queued_follow_up"
  const val HOST_UI_TOOL_ACTION: String = "host_ui_tool_action"
  const val RUNTIME_SERVICE_SUBAGENT_RECOVERY: String = "runtime_service_subagent_recovery"
  const val SCHEDULED_TRIGGER: String = "scheduled_trigger"
}

internal data class HostRuntimeLifecycleDescriptor(
  val processStartId: String = OpenCrayProcessLifecycle.processStartId,
  val processStartedAtEpochMs: Long = OpenCrayProcessLifecycle.processStartedAtEpochMs,
  val hostInstanceId: String = lifecycleId(prefix = "host"),
  val runtimeOwnerId: String = hostInstanceId,
  val runtimeControllerId: String = runtimeOwnerId,
  val hostCreatedAtEpochMs: Long = System.currentTimeMillis(),
  val durableRuntimeControllerId: String = runtimeControllerId,
) {
  fun snapshotMap(): Map<String, Any?> = mapOf(
    "processStartId" to processStartId,
    "processStartedAtEpochMs" to processStartedAtEpochMs,
    "hostInstanceId" to hostInstanceId,
    "runtimeOwnerId" to runtimeOwnerId,
    "runtimeControllerId" to runtimeControllerId,
    "durableRuntimeControllerId" to durableRuntimeControllerId,
    "hostCreatedAtEpochMs" to hostCreatedAtEpochMs,
  )

  fun taskMetadata(
    submissionSource: String? = null,
  ): Map<String, String> = buildMap {
    put(RunLifecycleMetadataKeys.PROCESS_START_ID, processStartId)
    put(RunLifecycleMetadataKeys.HOST_INSTANCE_ID, hostInstanceId)
    put(RunLifecycleMetadataKeys.RUNTIME_OWNER_ID, runtimeOwnerId)
    put(RunLifecycleMetadataKeys.RUNTIME_CONTROLLER_ID, runtimeControllerId)
    put(RunLifecycleMetadataKeys.DURABLE_RUNTIME_CONTROLLER_ID, durableRuntimeControllerId)
    put(
      RunLifecycleMetadataKeys.RUNTIME_EXECUTION_OWNERSHIP_TIER,
      RuntimeExecutionOwnershipTiers.RUNTIME_PROCESS,
    )
    put(RunLifecycleMetadataKeys.RUNTIME_CONTROLLER_PROCESS_SEPARATE, false.toString())
    put(RunLifecycleMetadataKeys.RUN_ATTEMPT, INITIAL_RUN_ATTEMPT.toString())
    submissionSource
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { normalized -> put(RunLifecycleMetadataKeys.SUBMISSION_SOURCE, normalized) }
  }

  fun stampTaskMetadata(
    metadata: Map<String, String>,
  ): Map<String, String> = metadata + taskMetadata()
}

internal fun submissionSourceTaskMetadata(
  submissionSource: String?,
): Map<String, String> = buildMap {
  submissionSource
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let { normalized -> put(RunLifecycleMetadataKeys.SUBMISSION_SOURCE, normalized) }
}

internal data class RunLifecycleDiagnostics(
  val processStartId: String? = null,
  val hostInstanceId: String? = null,
  val runtimeOwnerId: String? = null,
  val runtimeControllerId: String? = null,
  val durableRuntimeControllerId: String? = null,
  val runtimeExecutionOwnershipTier: String? = null,
  val runtimeControllerProcessSeparate: Boolean? = null,
  val runAttempt: Int? = null,
  val checkpointResumeAttemptCount: Int? = null,
  val recoveredFromCheckpointId: String? = null,
  val managedProcessReconnectProcessIds: List<String> = emptyList(),
  val managedProcessReconnectStatus: String? = null,
  val managedProcessReconnectRecoveryState: String? = null,
  val managedProcessReconnectRetryAfterEpochMs: Long? = null,
  val managedProcessReconnectAttemptCount: Int? = null,
  val managedProcessContinuationBasis: String? = null,
  val managedProcessRestoreScope: String? = null,
  val managedProcessRestoreDecision: String? = null,
  val submissionSource: String? = null,
  val recoveryReason: String? = null,
  val queueRestoreEpochMs: Long? = null,
  val previousLifecycleState: String? = null,
  val restoredFromDurableStore: Boolean? = null,
) {
  val isEmpty: Boolean
    get() = processStartId.isNullOrBlank() &&
      hostInstanceId.isNullOrBlank() &&
      runtimeOwnerId.isNullOrBlank() &&
      runtimeControllerId.isNullOrBlank() &&
      durableRuntimeControllerId.isNullOrBlank() &&
      runtimeExecutionOwnershipTier.isNullOrBlank() &&
      runtimeControllerProcessSeparate == null &&
      runAttempt == null &&
      checkpointResumeAttemptCount == null &&
      recoveredFromCheckpointId.isNullOrBlank() &&
      managedProcessReconnectProcessIds.isEmpty() &&
      managedProcessReconnectStatus.isNullOrBlank() &&
      managedProcessReconnectRecoveryState.isNullOrBlank() &&
      managedProcessReconnectRetryAfterEpochMs == null &&
      managedProcessReconnectAttemptCount == null &&
      managedProcessContinuationBasis.isNullOrBlank() &&
      managedProcessRestoreScope.isNullOrBlank() &&
      managedProcessRestoreDecision.isNullOrBlank() &&
      submissionSource.isNullOrBlank() &&
      recoveryReason.isNullOrBlank() &&
      queueRestoreEpochMs == null &&
      previousLifecycleState.isNullOrBlank() &&
      restoredFromDurableStore == null

  fun toMap(): Map<String, Any?> = buildMap {
    processStartId?.takeIf(String::isNotBlank)?.let { put("processStartId", it) }
    hostInstanceId?.takeIf(String::isNotBlank)?.let { put("hostInstanceId", it) }
    runtimeOwnerId?.takeIf(String::isNotBlank)?.let { put("runtimeOwnerId", it) }
    runtimeControllerId?.takeIf(String::isNotBlank)?.let { put("runtimeControllerId", it) }
    durableRuntimeControllerId
      ?.takeIf(String::isNotBlank)
      ?.let { put("durableRuntimeControllerId", it) }
    runtimeExecutionOwnershipTier
      ?.takeIf(String::isNotBlank)
      ?.let { put("runtimeExecutionOwnershipTier", it) }
    runtimeControllerProcessSeparate?.let { separate ->
      put("runtimeControllerProcessSeparate", separate)
    }
    runAttempt?.let { put("runAttempt", it) }
    checkpointResumeAttemptCount?.let { put("checkpointResumeAttemptCount", it) }
    recoveredFromCheckpointId?.takeIf(String::isNotBlank)?.let { put("recoveredFromCheckpointId", it) }
    if (managedProcessReconnectProcessIds.isNotEmpty()) {
      put("managedProcessReconnectProcessIds", managedProcessReconnectProcessIds)
    }
    managedProcessReconnectStatus
      ?.takeIf(String::isNotBlank)
      ?.let { put("managedProcessReconnectStatus", it) }
    managedProcessReconnectRecoveryState
      ?.takeIf(String::isNotBlank)
      ?.let { put("managedProcessReconnectRecoveryState", it) }
    managedProcessReconnectRetryAfterEpochMs
      ?.let { put("managedProcessReconnectRetryAfterEpochMs", it) }
    managedProcessReconnectAttemptCount
      ?.let { put("managedProcessReconnectAttemptCount", it) }
    managedProcessContinuationBasis
      ?.takeIf(String::isNotBlank)
      ?.let { put("managedProcessContinuationBasis", it) }
    managedProcessRestoreScope
      ?.takeIf(String::isNotBlank)
      ?.let { put("managedProcessRestoreScope", it) }
    managedProcessRestoreDecision
      ?.takeIf(String::isNotBlank)
      ?.let { put("managedProcessRestoreDecision", it) }
    submissionSource?.takeIf(String::isNotBlank)?.let { put("submissionSource", it) }
    recoveryReason?.takeIf(String::isNotBlank)?.let { put("recoveryReason", it) }
    queueRestoreEpochMs?.let { put("queueRestoreEpochMs", it) }
    previousLifecycleState?.takeIf(String::isNotBlank)?.let { put("previousLifecycleState", it) }
    restoredFromDurableStore?.let { put("restoredFromDurableStore", it) }
  }
}

internal fun runLifecycleDiagnosticsFrom(
  taskMetadata: Map<String, String>,
  resultMetadata: Map<String, String> = emptyMap(),
  resultErrorCode: String? = null,
): RunLifecycleDiagnostics {
  val recoveryReason = taskMetadata[METADATA_RECOVERY_REASON]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: managedProcessRecoveryReasonOrNull(
      resultMetadata = resultMetadata,
      resultErrorCode = resultErrorCode,
    )
  return RunLifecycleDiagnostics(
    processStartId = taskMetadata[RunLifecycleMetadataKeys.PROCESS_START_ID]?.trim(),
    hostInstanceId = taskMetadata[RunLifecycleMetadataKeys.HOST_INSTANCE_ID]?.trim(),
    runtimeOwnerId = taskMetadata[RunLifecycleMetadataKeys.RUNTIME_OWNER_ID]?.trim(),
    runtimeControllerId = taskMetadata[RunLifecycleMetadataKeys.RUNTIME_CONTROLLER_ID]?.trim(),
    durableRuntimeControllerId = taskMetadata[RunLifecycleMetadataKeys.DURABLE_RUNTIME_CONTROLLER_ID]?.trim(),
    runtimeExecutionOwnershipTier =
      taskMetadata[RunLifecycleMetadataKeys.RUNTIME_EXECUTION_OWNERSHIP_TIER]
        ?.trim()
        ?.takeIf(String::isNotBlank),
    runtimeControllerProcessSeparate =
      taskMetadata[RunLifecycleMetadataKeys.RUNTIME_CONTROLLER_PROCESS_SEPARATE]
        ?.trim()
        ?.toBooleanStrictOrNull(),
    runAttempt = taskMetadata[RunLifecycleMetadataKeys.RUN_ATTEMPT]
      ?.trim()
      ?.toIntOrNull()
      ?.takeIf { attempt -> attempt > 0 },
    checkpointResumeAttemptCount =
      (
        taskMetadata[RunLifecycleMetadataKeys.CHECKPOINT_RESUME_ATTEMPT_COUNT]
          ?: resultMetadata[RunLifecycleMetadataKeys.CHECKPOINT_RESUME_ATTEMPT_COUNT]
        )
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { count -> count >= 0 },
    recoveredFromCheckpointId = taskMetadata[RunLifecycleMetadataKeys.RECOVERED_FROM_CHECKPOINT_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank),
    managedProcessReconnectProcessIds =
      taskMetadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_PROCESS_IDS]
        ?.split(",")
        ?.mapNotNull { processId -> processId.trim().takeIf(String::isNotBlank) }
        .orEmpty(),
    managedProcessReconnectStatus =
      taskMetadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_STATUS]
        ?.trim()
        ?.takeIf(String::isNotBlank),
    managedProcessReconnectRecoveryState =
      taskMetadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RECOVERY_STATE]
        ?.trim()
        ?.takeIf(String::isNotBlank),
    managedProcessReconnectRetryAfterEpochMs =
      taskMetadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RETRY_AFTER_EPOCH_MS]
        ?.trim()
        ?.toLongOrNull(),
    managedProcessReconnectAttemptCount =
      taskMetadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_ATTEMPT_COUNT]
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { attempt -> attempt > 0 },
    managedProcessContinuationBasis =
      taskMetadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_CONTINUATION_BASIS]
        ?.trim()
        ?.takeIf(String::isNotBlank),
    managedProcessRestoreScope =
      taskMetadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_SCOPE]
        ?.trim()
        ?.takeIf(String::isNotBlank),
    managedProcessRestoreDecision =
      taskMetadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_DECISION]
        ?.trim()
        ?.takeIf(String::isNotBlank),
    submissionSource = taskMetadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE]?.trim(),
    recoveryReason = recoveryReason,
    queueRestoreEpochMs = taskMetadata[METADATA_QUEUE_RESTORE_EPOCH_MS]?.toLongOrNull(),
    previousLifecycleState = taskMetadata[METADATA_PREVIOUS_LIFECYCLE_STATE]
      ?.trim()
      ?.takeIf(String::isNotBlank),
    restoredFromDurableStore = resultMetadata[METADATA_RESTORED_FROM_DURABLE_STORE]
      ?.toBooleanStrictOrNull(),
  )
}

private fun managedProcessRecoveryReasonOrNull(
  resultMetadata: Map<String, String>,
  resultErrorCode: String?,
): String? {
  if (resultErrorCode != ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE) {
    return null
  }
  if (resultMetadata[METADATA_RUN_REPAIR_SOURCE] != RUN_REPAIR_SOURCE_MANAGED_PROCESS_RESTORE) {
    return null
  }
  return when (ManagedProcessRestoreScope.fromWireValue(resultMetadata[MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY])) {
    ManagedProcessRestoreScope.SAME_CONTROLLER ->
      RunLifecycleRecoveryReasons.MANAGED_PROCESS_RESTORE_INTERRUPTED_SAME_CONTROLLER

    ManagedProcessRestoreScope.SAME_PROCESS_NEW_CONTROLLER ->
      RunLifecycleRecoveryReasons.MANAGED_PROCESS_RESTORE_INTERRUPTED_SAME_PROCESS_NEW_CONTROLLER

    ManagedProcessRestoreScope.CROSS_PROCESS ->
      RunLifecycleRecoveryReasons.MANAGED_PROCESS_RESTORE_INTERRUPTED_CROSS_PROCESS

    ManagedProcessRestoreScope.UNKNOWN,
    null,
    -> RunLifecycleRecoveryReasons.MANAGED_PROCESS_RESTORE_INTERRUPTED
  }
}

internal object OpenCrayProcessLifecycle {
  val processStartedAtEpochMs: Long = System.currentTimeMillis()
  val processStartId: String = lifecycleId(
    prefix = "process",
    epochMs = processStartedAtEpochMs,
  )
}

internal fun lifecycleId(
  prefix: String,
  epochMs: Long = System.currentTimeMillis(),
): String = "$prefix-$epochMs-${UUID.randomUUID().toString().take(8)}"

private const val INITIAL_RUN_ATTEMPT: Int = 1
