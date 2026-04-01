package com.opencray.app

import com.opencray.core.orchestrator.METADATA_PREVIOUS_LIFECYCLE_STATE
import com.opencray.core.orchestrator.METADATA_QUEUE_RESTORE_EPOCH_MS
import com.opencray.core.orchestrator.METADATA_RECOVERY_REASON
import java.util.UUID

internal object RunLifecycleMetadataKeys {
  const val PROCESS_START_ID: String = "_host.processStartId"
  const val HOST_INSTANCE_ID: String = "_host.hostInstanceId"
  const val RUNTIME_OWNER_ID: String = "_host.runtimeOwnerId"
  const val SUBMISSION_SOURCE: String = "_host.submissionSource"
  const val PREAPPROVED_TOOL_NAME: String = "_host.preapprovedToolName"
}

internal object RunLifecycleRecoveryReasons {
  const val MANAGED_PROCESS_RESTORE_INTERRUPTED: String = "managed_process_restore_interrupted"
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
  val hostCreatedAtEpochMs: Long = System.currentTimeMillis(),
) {
  fun snapshotMap(): Map<String, Any?> = mapOf(
    "processStartId" to processStartId,
    "processStartedAtEpochMs" to processStartedAtEpochMs,
    "hostInstanceId" to hostInstanceId,
    "runtimeOwnerId" to runtimeOwnerId,
    "hostCreatedAtEpochMs" to hostCreatedAtEpochMs,
  )

  fun taskMetadata(
    submissionSource: String? = null,
  ): Map<String, String> = buildMap {
    put(RunLifecycleMetadataKeys.PROCESS_START_ID, processStartId)
    put(RunLifecycleMetadataKeys.HOST_INSTANCE_ID, hostInstanceId)
    put(RunLifecycleMetadataKeys.RUNTIME_OWNER_ID, runtimeOwnerId)
    submissionSource
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { normalized -> put(RunLifecycleMetadataKeys.SUBMISSION_SOURCE, normalized) }
  }
}

internal data class RunLifecycleDiagnostics(
  val processStartId: String? = null,
  val hostInstanceId: String? = null,
  val runtimeOwnerId: String? = null,
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
      submissionSource.isNullOrBlank() &&
      recoveryReason.isNullOrBlank() &&
      queueRestoreEpochMs == null &&
      previousLifecycleState.isNullOrBlank() &&
      restoredFromDurableStore == null

  fun toMap(): Map<String, Any?> = buildMap {
    processStartId?.takeIf(String::isNotBlank)?.let { put("processStartId", it) }
    hostInstanceId?.takeIf(String::isNotBlank)?.let { put("hostInstanceId", it) }
    runtimeOwnerId?.takeIf(String::isNotBlank)?.let { put("runtimeOwnerId", it) }
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
  return if (resultMetadata[METADATA_RUN_REPAIR_SOURCE] == RUN_REPAIR_SOURCE_MANAGED_PROCESS_RESTORE) {
    RunLifecycleRecoveryReasons.MANAGED_PROCESS_RESTORE_INTERRUPTED
  } else {
    null
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
