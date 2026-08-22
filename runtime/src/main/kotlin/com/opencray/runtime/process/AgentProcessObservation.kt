package com.opencray.runtime.process

import com.opencray.core.contracts.AgentTask
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.policy.ToolResultEnvelope
import com.opencray.runtime.policy.ToolResultLimitKind
import com.opencray.runtime.policy.ToolTargetKind
import com.opencray.runtime.policy.ToolWorkspaceRelation
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths

private const val MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_FULL_SNAPSHOT: String = "full_snapshot"
private const val MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_DELTA: String = "delta"
private const val MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_NO_CHANGE: String = "no_change"
private const val MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_RESET_FULL: String = "reset_full"

data class ManagedProcessObservationCursorState(
  val mode: String,
  val cursor: String,
  val stdoutBytes: Long,
  val stderrBytes: Long,
  val providerMode: String? = null,
  val providerCursor: String? = null,
  val providerEventCount: Long? = null,
)

internal data class ManagedProcessProviderObservationBoundary(
  val cursorBefore: String,
  val cursorAfter: String,
  val eventCountBefore: Long? = null,
  val eventCountAfter: Long? = null,
)

internal data class ManagedProcessObservationDelivery(
  val stdout: String,
  val stderr: String,
  val metadata: Map<String, String>,
  val renderLines: List<String>,
) {
  companion object {
    fun fullSnapshot(snapshot: ManagedProcessSnapshot): ManagedProcessObservationDelivery =
      ManagedProcessObservationDelivery(
        stdout = snapshot.stdout,
        stderr = snapshot.stderr,
        metadata = emptyMap(),
        renderLines = emptyList(),
      )

    fun snapshotMode(
      snapshot: ManagedProcessSnapshot,
      mode: String,
      cursorBefore: String,
      cursorAfter: String,
      stdoutDeltaBytes: Long,
      stderrDeltaBytes: Long,
      providerBoundary: ManagedProcessProviderObservationBoundary? = null,
      warning: String? = null,
    ): ManagedProcessObservationDelivery = deltaMode(
      mode = mode,
      cursorBefore = cursorBefore,
      cursorAfter = cursorAfter,
      stdout = snapshot.stdout,
      stderr = snapshot.stderr,
      stdoutDeltaBytes = stdoutDeltaBytes,
      stderrDeltaBytes = stderrDeltaBytes,
      providerBoundary = providerBoundary,
      warning = warning,
    )

    fun deltaMode(
      mode: String,
      cursorBefore: String,
      cursorAfter: String,
      stdout: String,
      stderr: String,
      stdoutDeltaBytes: Long,
      stderrDeltaBytes: Long,
      providerBoundary: ManagedProcessProviderObservationBoundary? = null,
      warning: String? = null,
    ): ManagedProcessObservationDelivery {
      val metadata = buildMap {
        put("sandboxCommandObservationDeliveryMode", mode)
        put("sandboxCommandObservationCursorBefore", cursorBefore)
        put("sandboxCommandObservationCursorAfter", cursorAfter)
        put("sandboxCommandObservationStdoutDeltaBytes", stdoutDeltaBytes.toString())
        put("sandboxCommandObservationStderrDeltaBytes", stderrDeltaBytes.toString())
        providerBoundary?.let { boundary ->
          put("sandboxCommandProviderObservationCursorBefore", boundary.cursorBefore)
          put("sandboxCommandProviderObservationCursorAfter", boundary.cursorAfter)
          boundary.eventCountBefore?.let { eventCount ->
            put("sandboxCommandProviderObservationEventCountBefore", eventCount.toString())
          }
          boundary.eventCountAfter?.let { eventCount ->
            put("sandboxCommandProviderObservationEventCountAfter", eventCount.toString())
          }
        }
        warning?.trim()?.takeIf(String::isNotBlank)?.let { message ->
          put("sandboxCommandObservationDeliveryWarning", message)
        }
      }
      val renderLines = buildList {
        add("sandbox_command_observation_delivery_mode=$mode")
        add("sandbox_command_observation_cursor_before=$cursorBefore")
        add("sandbox_command_observation_cursor_after=$cursorAfter")
        add("sandbox_command_observation_stdout_delta_bytes=$stdoutDeltaBytes")
        add("sandbox_command_observation_stderr_delta_bytes=$stderrDeltaBytes")
        providerBoundary?.let { boundary ->
          add("sandbox_command_provider_observation_cursor_before=${boundary.cursorBefore}")
          add("sandbox_command_provider_observation_cursor_after=${boundary.cursorAfter}")
          boundary.eventCountBefore?.let { eventCount ->
            add("sandbox_command_provider_observation_event_count_before=$eventCount")
          }
          boundary.eventCountAfter?.let { eventCount ->
            add("sandbox_command_provider_observation_event_count_after=$eventCount")
          }
        }
        warning?.trim()?.takeIf(String::isNotBlank)?.let { message ->
          add("observation_warning=$message")
        }
      }
      return ManagedProcessObservationDelivery(
        stdout = stdout,
        stderr = stderr,
        metadata = metadata,
        renderLines = renderLines,
      )
    }
  }
}
internal val MANAGED_PROCESS_RESERVED_METADATA_KEYS: Set<String> = setOf(
  "capabilityKind",
  "targetKind",
  "workspaceRelation",
  "primaryTargetPath",
  "secondaryTargetPath",
  "targetSummary",
  "executionMode",
  "policyOutcome",
  "policyReasonCode",
  "approvalRisk",
  "intentCategory",
  "executionIntentKind",
  "executionTransport",
  "executionCommandPreview",
  "executionScriptPath",
  "executionWorkingDirectory",
  "processLifecycleIntentKind",
  "intentProcessId",
  "intentWorkingDirectory",
  "resultLimitApplied",
  "resultTruncated",
  "resultLimitKind",
  "managedProcessRestoreScope",
  "managedProcessRestoreCurrentProcessStartId",
  "managedProcessRestoreCurrentRuntimeControllerId",
  "managedProcessRestoreCurrentDurableRuntimeControllerId",
)

internal fun OpenCrayToolDispatcher.toolStatusForManagedProcessStart(
    snapshot: ManagedProcessSnapshot,
  ): AgentToolResultStatus = when (snapshot.status) {
    ManagedProcessStatus.RUNNING,
    ManagedProcessStatus.SUCCESS,
    -> AgentToolResultStatus.SUCCESS

    ManagedProcessStatus.CANCELLED -> AgentToolResultStatus.CANCELLED
    ManagedProcessStatus.TIMEOUT -> AgentToolResultStatus.TIMEOUT
    ManagedProcessStatus.FAILED,
    ManagedProcessStatus.SPAWN_ERROR,
    -> AgentToolResultStatus.FAILED
  }

internal fun OpenCrayToolDispatcher.managedProcessToolResult(
    toolName: String,
    status: AgentToolResultStatus,
    content: String,
    snapshot: ManagedProcessSnapshot,
    stdout: String = snapshot.stdout,
    stderr: String = snapshot.stderr,
    metadata: Map<String, String>,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = status,
    content = content,
    exitCode = snapshot.exitCode,
    stdout = stdout,
    stderr = stderr,
    errorCode = snapshot.errorCode,
    errorMessage = snapshot.errorMessage,
    metadata = metadata,
  )

internal fun OpenCrayToolDispatcher.missingManagedProcess(
    processId: String,
    toolName: String,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.FAILED,
    content = "Managed process '$processId' was not found.",
    errorCode = "PROCESS_NOT_FOUND",
    errorMessage = "Managed process '$processId' was not found.",
    metadata = toolPolicySupport.commonMetadata(
      toolName = toolName,
      metadataContext = policyMetadataContext(
        toolName = toolName,
        targetKind = ToolTargetKind.PROCESS,
        workspaceRelation = ToolWorkspaceRelation.NONE,
        targetSummary = processId,
      ),
    ) + mapOf("processId" to processId),
  )

internal fun OpenCrayToolDispatcher.managedProcessWorkingDirectoryPath(snapshot: ManagedProcessSnapshot): Path? =
    snapshot.workingDirectory
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { candidate -> runCatching { Paths.get(candidate) }.getOrNull() }

internal fun OpenCrayToolDispatcher.managedProcessOwnerIdentity(task: AgentTask): ManagedProcessRuntimeIdentity? {
    val processStartId = task.metadata["_host.processStartId"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val runtimeControllerId = task.metadata["_host.runtimeControllerId"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val durableRuntimeControllerId = task.metadata["_host.durableRuntimeControllerId"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    return ManagedProcessRuntimeIdentity(
      processStartId = processStartId,
      runtimeControllerId = runtimeControllerId,
      durableRuntimeControllerId = durableRuntimeControllerId,
    ).takeUnless(ManagedProcessRuntimeIdentity::isEmpty)
  }

internal fun OpenCrayToolDispatcher.managedProcessMetadata(snapshot: ManagedProcessSnapshot): Map<String, String> = buildMap {
    val normalizedSnapshot = snapshot.withNormalizedRemoteState()
    put("processId", normalizedSnapshot.processId)
    put("processStatus", normalizedSnapshot.status.name)
    put("processStarted", normalizedSnapshot.processStarted.toString())
    put("timeoutMs", normalizedSnapshot.timeoutMs.toString())
    put("command", normalizedSnapshot.command)
    if (normalizedSnapshot.args.isNotEmpty()) {
      put("args", normalizedSnapshot.args.joinToString(separator = "\u0000"))
    }
    toolTargetResolver.displayWorkingDirectory(normalizedSnapshot.workingDirectory)?.let { workingDirectory ->
      put("workingDirectory", workingDirectory)
    }
    normalizedSnapshot.exitCode?.let { code -> put("exitCode", code.toString()) }
    normalizedSnapshot.finishedAtEpochMs?.let { finishedAt -> put("finishedAtEpochMs", finishedAt.toString()) }
    put("startedAtEpochMs", normalizedSnapshot.startedAtEpochMs.toString())
    put("updatedAtEpochMs", normalizedSnapshot.updatedAtEpochMs.toString())
    if (normalizedSnapshot.timedOut) {
      put("timedOut", "true")
    }
    if (normalizedSnapshot.cancelled) {
      put("cancelled", "true")
    }
    if (normalizedSnapshot.outputLimitExceeded) {
      put("outputLimitExceeded", "true")
    }
    putAll(normalizedSnapshot.metadata.filterKeys { key -> isManagedProcessRuntimeMetadataKey(key) })
  }

internal fun OpenCrayToolDispatcher.managedProcessResultEnvelope(
    snapshot: ManagedProcessSnapshot,
  ): ToolResultEnvelope = ToolResultEnvelope(
    limitApplied = true,
    truncated = snapshot.outputLimitExceeded,
    limitKind = ToolResultLimitKind.PROCESS_OUTPUT_BYTE_LIMIT,
  )

internal fun OpenCrayToolDispatcher.commandResultEnvelope(
    result: AgentToolResult,
  ): ToolResultEnvelope = ToolResultEnvelope(
    limitApplied = true,
    truncated = result.errorCode == "OUTPUT_LIMIT_EXCEEDED",
    limitKind = ToolResultLimitKind.COMMAND_OUTPUT_BYTE_LIMIT,
  )

internal fun OpenCrayToolDispatcher.isManagedProcessRuntimeMetadataKey(key: String): Boolean = key !in MANAGED_PROCESS_RESERVED_METADATA_KEYS

internal fun OpenCrayToolDispatcher.renderManagedProcessSnapshot(
    snapshot: ManagedProcessSnapshot,
    includeOutput: Boolean,
    observationDelivery: ManagedProcessObservationDelivery = ManagedProcessObservationDelivery.fullSnapshot(snapshot),
  ): String = buildString {
    val normalizedSnapshot = snapshot.withNormalizedRemoteState()
    appendLine("process_id=${normalizedSnapshot.processId}")
    appendLine("status=${normalizedSnapshot.status.name.lowercase()}")
    normalizedSnapshot.metadata["shellKind"]?.let { shellKind ->
      appendLine("shell_kind=$shellKind")
    }
    normalizedSnapshot.metadata["shellCommand"]?.let { shellCommand ->
      appendLine("shell_command=$shellCommand")
    }
    appendLine("command=${normalizedSnapshot.command}")
    normalizedSnapshot.metadata["runtimeKind"]?.let { runtimeKind ->
      appendLine("runtime_kind=$runtimeKind")
    }
    appendManagedProcessMetadataLine(normalizedSnapshot, "runtimeBackend", "runtime_backend")
    appendManagedProcessMetadataLine(normalizedSnapshot, "runtimeTransport", "runtime_transport")
    normalizedSnapshot.metadata["scriptPath"]?.let { scriptPath ->
      appendLine("script_path=$scriptPath")
    }
    normalizedSnapshot.metadata["pythonExecutable"]?.let { pythonExecutable ->
      appendLine("python_executable=$pythonExecutable")
    }
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandBackendKind", "sandbox_backend_kind")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandBackendResolvedKind", "sandbox_backend_resolved_kind")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandProviderNative", "sandbox_provider_native")
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandSupportsStreamingLogs",
      "sandbox_supports_streaming_logs",
    )
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandSupportsReconnect", "sandbox_supports_reconnect")
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandSupportsManagedProcessLiveObservation",
      "sandbox_supports_managed_process_live_observation",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandSupportsManagedProcessObservationCursorResume",
      "sandbox_supports_managed_process_observation_cursor_resume",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandSupportsManagedProcessObservationBackfill",
      "sandbox_supports_managed_process_observation_backfill",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderHandleKind",
      "sandbox_command_provider_handle_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderStableSelectorKind",
      "sandbox_command_provider_stable_selector_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderStableSelectorValue",
      "sandbox_command_provider_stable_selector_value",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderLiveSelectorKind",
      "sandbox_command_provider_live_selector_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderLiveSelectorValue",
      "sandbox_command_provider_live_selector_value",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandIdKind",
      "sandbox_command_id_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandId",
      "sandbox_command_id",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderObservationMode",
      "sandbox_command_provider_observation_mode",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderObservationEventCount",
      "sandbox_command_provider_observation_event_count",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderObservationCursor",
      "sandbox_command_provider_observation_cursor",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderObservationBackfillSupported",
      "sandbox_command_provider_observation_backfill_supported",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderObservationResumeContract",
      "sandbox_command_provider_observation_resume_contract",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderObservationResumeBlocker",
      "sandbox_command_provider_observation_resume_blocker",
    )
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandHandleIdKind", "sandbox_command_handle_id_kind")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandHandleId", "sandbox_command_handle_id")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandHandleTag", "sandbox_command_handle_tag")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandObservationMode", "sandbox_observation_mode")
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandObservationEventCount",
      "sandbox_command_observation_event_count",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandObservationCursor",
      "sandbox_command_observation_cursor",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandObservationStdoutBytes",
      "sandbox_command_observation_stdout_bytes",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandObservationStderrBytes",
      "sandbox_command_observation_stderr_bytes",
    )
    observationDelivery.renderLines.forEach(::appendLine)
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandApi", "sandbox_command_api")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandReconnectApi", "sandbox_command_reconnect_api")
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectStatus",
      "sandbox_command_reconnect_status",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectRecoveryState",
      "sandbox_command_reconnect_recovery_state",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSource",
      "sandbox_command_reconnect_source",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectHttpStatusCode",
      "sandbox_command_reconnect_http_status_code",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectResumeMode",
      "sandbox_command_reconnect_resume_mode",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectBackfillSupported",
      "sandbox_command_reconnect_backfill_supported",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectOutputGapRisk",
      "sandbox_command_reconnect_output_gap_risk",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectRetryable",
      "sandbox_command_reconnect_retryable",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectRetryAfterEpochMs",
      "sandbox_command_reconnect_retry_after_epoch_ms",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectLastAttachedAtEpochMs",
      "sandbox_command_reconnect_last_attached_at_epoch_ms",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectLastEventAtEpochMs",
      "sandbox_command_reconnect_last_event_at_epoch_ms",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectLastEventKind",
      "sandbox_command_reconnect_last_event_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectLastFailureAtEpochMs",
      "sandbox_command_reconnect_last_failure_at_epoch_ms",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectAttemptCount",
      "sandbox_command_reconnect_attempt_count",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSelectorKind",
      "sandbox_command_reconnect_selector_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSelectorValue",
      "sandbox_command_reconnect_selector_value",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSelectorSource",
      "sandbox_command_reconnect_selector_source",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeedObservationCursor",
      "sandbox_command_reconnect_seed_observation_cursor",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeedProviderObservationCursor",
      "sandbox_command_reconnect_seed_provider_observation_cursor",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeedEventCount",
      "sandbox_command_reconnect_seed_event_count",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeedProviderObservationEventCount",
      "sandbox_command_reconnect_seed_provider_observation_event_count",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeedSource",
      "sandbox_command_reconnect_seed_source",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectProviderObservationSeedConsumed",
      "sandbox_command_reconnect_provider_observation_seed_consumed",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectProviderObservationSeedState",
      "sandbox_command_reconnect_provider_observation_seed_state",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectProviderObservationSeedSource",
      "sandbox_command_reconnect_provider_observation_seed_source",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectProviderObservationResumeApplied",
      "sandbox_command_reconnect_provider_observation_resume_applied",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectProviderObservationResumeReason",
      "sandbox_command_reconnect_provider_observation_resume_reason",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs",
      "sandbox_command_reconnect_provider_observation_seed_consumed_at_epoch_ms",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeededStdoutBytes",
      "sandbox_command_reconnect_seeded_stdout_bytes",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeededStderrBytes",
      "sandbox_command_reconnect_seeded_stderr_bytes",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandNativeProtocol",
      "sandbox_command_native_protocol",
    )
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandSessionSource", "sandbox_command_session_source")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandPid", "sandbox_command_pid")
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandNativeProcessStatus",
      "sandbox_command_native_process_status",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandNativeFailureStage",
      "sandbox_command_native_failure_stage",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectFailureStage",
      "sandbox_command_reconnect_failure_stage",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandBackendFallbackReasonCode",
      "sandbox_backend_fallback_reason",
    )
    val reconnectProviderObservationSeedState =
      normalizedSnapshot.metadata["sandboxCommandReconnectProviderObservationSeedState"]
    if (normalizedSnapshot.metadata["sandboxCommandReconnectOutputGapRisk"] == "true") {
      when (reconnectProviderObservationSeedState) {
        "pending_live_attach" -> appendLine(
          "observation_warning=provider reconnect restored a persisted output seed and is still waiting for live attach; current output may only reflect the persisted host snapshot",
        )

        "retry_scheduled_before_live_attach",
        "failed_terminal_before_live_attach",
        -> Unit

        else -> appendLine(
          "observation_warning=provider reconnect resumed from persisted snapshot without log backfill; output emitted before attach may be missing",
        )
      }
    }
    if (
      normalizedSnapshot.metadata["sandboxCommandReconnectRecoveryState"] == "retry_scheduled" ||
      normalizedSnapshot.metadata["sandboxCommandReconnectRetryable"] == "true"
    ) {
      appendLine(
        when (reconnectProviderObservationSeedState) {
          "retry_scheduled_before_live_attach" ->
            "observation_warning=provider reconnect has not yet reattached live output; current output still reflects the persisted host snapshot seed and a later ProcessRead or ProcessWait may retry attach after backoff"

          else ->
            "observation_warning=provider reconnect failed without terminal process state; a later ProcessRead or ProcessWait may retry attach after backoff"
        },
      )
    }
    if (normalizedSnapshot.metadata["sandboxCommandReconnectRecoveryState"] == "failed_terminal") {
      appendLine(
        "observation_warning=provider reconnect terminated before live attach; current output may only reflect the persisted host snapshot",
      )
    }
    normalizedSnapshot.metadata["terminationSupport"]?.let { terminationSupport ->
      appendLine("termination_support=$terminationSupport")
    }
    if (normalizedSnapshot.metadata["terminationRequested"] == "true") {
      appendLine("termination_requested=true")
    }
    normalizedSnapshot.metadata["terminationRequestAccepted"]?.let { terminationRequestAccepted ->
      appendLine("termination_request_accepted=$terminationRequestAccepted")
    }
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandTerminateApi", "sandbox_command_terminate_api")
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandTerminateRequestedSignal",
      "sandbox_command_terminate_requested_signal",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandTerminateSelectorKind",
      "sandbox_command_terminate_selector_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandTerminateSelectorValue",
      "sandbox_command_terminate_selector_value",
    )
    if (normalizedSnapshot.args.isNotEmpty()) {
      appendLine("args=${normalizedSnapshot.args.joinToString(separator = " ")}")
    }
    toolTargetResolver.displayWorkingDirectory(normalizedSnapshot.workingDirectory)?.let { workingDirectory ->
      appendLine("working_directory=$workingDirectory")
    }
    appendLine("timeout_ms=${normalizedSnapshot.timeoutMs}")
    appendLine("process_started=${normalizedSnapshot.processStarted}")
    normalizedSnapshot.exitCode?.let { code ->
      appendLine("exit_code=$code")
    }
    normalizedSnapshot.errorCode?.let { code ->
      appendLine("error_code=$code")
    }
    normalizedSnapshot.errorMessage?.let { message ->
      appendLine("error_message=$message")
    }
    appendLine("started_at_epoch_ms=${normalizedSnapshot.startedAtEpochMs}")
    appendLine("updated_at_epoch_ms=${normalizedSnapshot.updatedAtEpochMs}")
    normalizedSnapshot.finishedAtEpochMs?.let { finishedAt ->
      appendLine("finished_at_epoch_ms=$finishedAt")
    }
    if (includeOutput) {
      if (observationDelivery.stdout.isNotBlank()) {
        appendLine()
        appendLine("[stdout]")
        appendLine(observationDelivery.stdout.trimEnd())
      }
      if (observationDelivery.stderr.isNotBlank()) {
        appendLine()
        appendLine("[stderr]")
        append(observationDelivery.stderr.trimEnd())
      }
    }
  }.trim()

internal fun OpenCrayToolDispatcher.observeManagedProcessOutput(
    snapshot: ManagedProcessSnapshot,
  ): ManagedProcessObservationDelivery {
    val current = managedProcessObservationCursorState(snapshot)
      ?: return ManagedProcessObservationDelivery.fullSnapshot(snapshot)
    val previous = managedProcessObservationTracker.recordAndReturnPrevious(
      processId = snapshot.processId,
      current = current,
    )
    if (previous != null) {
      return deliverManagedProcessObservationDelta(
        snapshot = snapshot,
        current = current,
        previous = previous,
        resetWarning = "host observation cursor regressed or output window changed; returning full snapshot output",
        stdoutAlignmentWarning = "host observation cursor could not be aligned with stdout bytes; returning full snapshot output",
        stderrAlignmentWarning = "host observation cursor could not be aligned with stderr bytes; returning full snapshot output",
      )
    }
    val persistedDelivery = managedProcessDeliveredObservationCursorState(snapshot)
    if (persistedDelivery != null) {
      return deliverManagedProcessObservationDelta(
        snapshot = snapshot,
        current = current,
        previous = persistedDelivery,
        resetWarning = "persisted delivered observation cursor regressed or output window changed; returning full snapshot output",
        stdoutAlignmentWarning = "persisted delivered observation cursor could not be aligned with stdout bytes; returning full snapshot output",
        stderrAlignmentWarning = "persisted delivered observation cursor could not be aligned with stderr bytes; returning full snapshot output",
      )
    }
    val reconnectSeed = managedProcessReconnectSeedObservationCursorState(snapshot)
    if (reconnectSeed != null) {
      return deliverManagedProcessObservationDelta(
        snapshot = snapshot,
        current = current,
        previous = reconnectSeed,
        resetWarning = "persisted reconnect seed cursor regressed or output window changed; returning full snapshot output",
        stdoutAlignmentWarning = "persisted reconnect seed could not be aligned with stdout bytes; returning full snapshot output",
        stderrAlignmentWarning = "persisted reconnect seed could not be aligned with stderr bytes; returning full snapshot output",
      )
    }
    return ManagedProcessObservationDelivery.snapshotMode(
      snapshot = snapshot,
      mode = MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_FULL_SNAPSHOT,
      cursorBefore = "none",
      cursorAfter = current.cursor,
      stdoutDeltaBytes = current.stdoutBytes,
      stderrDeltaBytes = current.stderrBytes,
    )
  }

internal fun OpenCrayToolDispatcher.deliverManagedProcessObservationDelta(
    snapshot: ManagedProcessSnapshot,
    current: ManagedProcessObservationCursorState,
    previous: ManagedProcessObservationCursorState,
    resetWarning: String,
    stdoutAlignmentWarning: String,
    stderrAlignmentWarning: String,
  ): ManagedProcessObservationDelivery {
    val providerBoundary = managedProcessProviderObservationBoundary(current = current, previous = previous)
    if (
      current.cursor == previous.cursor &&
      current.stdoutBytes == previous.stdoutBytes &&
      current.stderrBytes == previous.stderrBytes
    ) {
      return ManagedProcessObservationDelivery.deltaMode(
        mode = MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_NO_CHANGE,
        cursorBefore = previous.cursor,
        cursorAfter = current.cursor,
        stdout = "",
        stderr = "",
        stdoutDeltaBytes = 0L,
        stderrDeltaBytes = 0L,
        providerBoundary = providerBoundary,
      )
    }
    managedProcessProviderObservationResetWarning(
      current = current,
      previous = previous,
    )?.let { warning ->
      return ManagedProcessObservationDelivery.snapshotMode(
        snapshot = snapshot,
        mode = MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_RESET_FULL,
        cursorBefore = previous.cursor,
        cursorAfter = current.cursor,
        stdoutDeltaBytes = current.stdoutBytes,
        stderrDeltaBytes = current.stderrBytes,
        providerBoundary = providerBoundary,
        warning = warning,
      )
    }
    if (current.stdoutBytes < previous.stdoutBytes || current.stderrBytes < previous.stderrBytes) {
      return ManagedProcessObservationDelivery.snapshotMode(
        snapshot = snapshot,
        mode = MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_RESET_FULL,
        cursorBefore = previous.cursor,
        cursorAfter = current.cursor,
        stdoutDeltaBytes = current.stdoutBytes,
        stderrDeltaBytes = current.stderrBytes,
        providerBoundary = providerBoundary,
        warning = resetWarning,
      )
    }
    val stdoutDelta = utf8DeltaFromByteOffset(snapshot.stdout, previous.stdoutBytes)
      ?: return ManagedProcessObservationDelivery.snapshotMode(
        snapshot = snapshot,
        mode = MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_RESET_FULL,
        cursorBefore = previous.cursor,
        cursorAfter = current.cursor,
        stdoutDeltaBytes = current.stdoutBytes,
        stderrDeltaBytes = current.stderrBytes,
        providerBoundary = providerBoundary,
        warning = stdoutAlignmentWarning,
      )
    val stderrDelta = utf8DeltaFromByteOffset(snapshot.stderr, previous.stderrBytes)
      ?: return ManagedProcessObservationDelivery.snapshotMode(
        snapshot = snapshot,
        mode = MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_RESET_FULL,
        cursorBefore = previous.cursor,
        cursorAfter = current.cursor,
        stdoutDeltaBytes = current.stdoutBytes,
        stderrDeltaBytes = current.stderrBytes,
        providerBoundary = providerBoundary,
        warning = stderrAlignmentWarning,
      )
    return ManagedProcessObservationDelivery.deltaMode(
      mode = if (stdoutDelta.isBlank() && stderrDelta.isBlank()) {
        MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_NO_CHANGE
      } else {
        MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_DELTA
      },
      cursorBefore = previous.cursor,
      cursorAfter = current.cursor,
      stdout = stdoutDelta,
      stderr = stderrDelta,
      stdoutDeltaBytes = (current.stdoutBytes - previous.stdoutBytes).coerceAtLeast(0L),
      stderrDeltaBytes = (current.stderrBytes - previous.stderrBytes).coerceAtLeast(0L),
      providerBoundary = providerBoundary,
    )
  }

internal fun OpenCrayToolDispatcher.recordManagedProcessObservationDelivery(
    snapshot: ManagedProcessSnapshot,
  ) {
    val current = managedProcessObservationCursorState(snapshot)
    processRegistry.recordObservationDelivery(
      processId = snapshot.processId,
      deliveredObservationState = current?.toDeliveredObservationState(snapshot),
    )
  }

internal fun OpenCrayToolDispatcher.managedProcessObservationCursorState(
    snapshot: ManagedProcessSnapshot,
  ): ManagedProcessObservationCursorState? {
    val observationState = snapshot.normalizedObservationState()
    val mode = observationState?.mode ?: snapshot.metadata["sandboxCommandObservationMode"]
    if (mode != "host_managed_snapshot") {
      return null
    }
    val cursor = observationState?.hostCursor
      ?: snapshot.metadata["sandboxCommandObservationCursor"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    val stdoutBytes = observationState?.stdoutBytes
      ?: snapshot.metadata["sandboxCommandObservationStdoutBytes"]?.toLongOrNull()
      ?: snapshot.stdout.toUtf8Length()
    val stderrBytes = observationState?.stderrBytes
      ?: snapshot.metadata["sandboxCommandObservationStderrBytes"]?.toLongOrNull()
      ?: snapshot.stderr.toUtf8Length()
    if (stdoutBytes < 0L || stderrBytes < 0L) {
      return null
    }
    return ManagedProcessObservationCursorState(
      mode = mode,
      cursor = cursor,
      stdoutBytes = stdoutBytes,
      stderrBytes = stderrBytes,
      providerMode = observationState?.providerMode
        ?: snapshot.metadata["sandboxCommandProviderObservationMode"]?.trim()?.takeIf(String::isNotBlank),
      providerCursor = observationState?.providerCursor
        ?: snapshot.metadata["sandboxCommandProviderObservationCursor"]?.trim()?.takeIf(String::isNotBlank),
      providerEventCount = observationState?.providerEventCount
        ?: snapshot.metadata["sandboxCommandProviderObservationEventCount"]?.toLongOrNull()
          ?.takeIf { eventCount -> eventCount >= 0L },
    )
  }

internal fun OpenCrayToolDispatcher.managedProcessDeliveredObservationCursorState(
    snapshot: ManagedProcessSnapshot,
  ): ManagedProcessObservationCursorState? {
    val deliveredObservationState = snapshot.normalizedDeliveredObservationState()
    val mode =
      deliveredObservationState?.mode
        ?: snapshot.metadata["sandboxCommandLastDeliveredObservationMode"]
        ?: return null
    if (mode != "host_managed_snapshot") {
      return null
    }
    val cursor =
      deliveredObservationState?.cursor
        ?: snapshot.metadata["sandboxCommandLastDeliveredObservationCursor"]
          ?.trim()
          ?.takeIf(String::isNotBlank)
        ?: return null
    val stdoutBytes =
      deliveredObservationState?.stdoutBytes
        ?: snapshot.metadata["sandboxCommandLastDeliveredStdoutBytes"]?.toLongOrNull()
        ?: return null
    val stderrBytes =
      deliveredObservationState?.stderrBytes
        ?: snapshot.metadata["sandboxCommandLastDeliveredStderrBytes"]?.toLongOrNull()
        ?: return null
    if (stdoutBytes < 0L || stderrBytes < 0L) {
      return null
    }
    return ManagedProcessObservationCursorState(
      mode = mode,
      cursor = cursor,
      stdoutBytes = stdoutBytes,
      stderrBytes = stderrBytes,
      providerMode =
        deliveredObservationState?.providerMode
          ?: snapshot.metadata["sandboxCommandLastDeliveredProviderObservationMode"]
            ?.trim()
            ?.takeIf(String::isNotBlank),
      providerCursor =
        deliveredObservationState?.providerCursor
          ?: snapshot.metadata["sandboxCommandLastDeliveredProviderObservationCursor"]
            ?.trim()
            ?.takeIf(String::isNotBlank),
      providerEventCount =
        deliveredObservationState?.providerEventCount
          ?: snapshot.metadata["sandboxCommandLastDeliveredProviderObservationEventCount"]
            ?.toLongOrNull()
            ?.takeIf { eventCount -> eventCount >= 0L },
    )
  }

internal fun OpenCrayToolDispatcher.managedProcessReconnectSeedObservationCursorState(
    snapshot: ManagedProcessSnapshot,
  ): ManagedProcessObservationCursorState? {
    val reconnectSeed = snapshot.normalizedReconnectState()?.seed
    val seedSource = reconnectSeed?.source ?: snapshot.metadata["sandboxCommandReconnectSeedSource"]
    if (seedSource?.trim().isNullOrBlank()) {
      return null
    }
    val cursor = reconnectSeed?.hostObservationCursor
      ?: snapshot.metadata["sandboxCommandReconnectSeedObservationCursor"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    val stdoutBytes = reconnectSeed?.stdoutBytes
      ?: snapshot.metadata["sandboxCommandReconnectSeededStdoutBytes"]?.toLongOrNull()
      ?: return null
    val stderrBytes = reconnectSeed?.stderrBytes
      ?: snapshot.metadata["sandboxCommandReconnectSeededStderrBytes"]?.toLongOrNull()
      ?: return null
    if (stdoutBytes < 0L || stderrBytes < 0L) {
      return null
    }
    return ManagedProcessObservationCursorState(
      mode = "host_managed_snapshot",
      cursor = cursor,
      stdoutBytes = stdoutBytes,
      stderrBytes = stderrBytes,
      providerMode = snapshot.normalizedObservationState()?.providerMode
        ?: snapshot.metadata["sandboxCommandProviderObservationMode"]?.trim()?.takeIf(String::isNotBlank),
      providerCursor = reconnectSeed?.providerObservationCursor
        ?: snapshot.metadata["sandboxCommandReconnectSeedProviderObservationCursor"]
          ?.trim()
          ?.takeIf(String::isNotBlank),
      providerEventCount = reconnectSeed?.providerObservationEventCount
        ?: snapshot.metadata["sandboxCommandReconnectSeedProviderObservationEventCount"]
          ?.toLongOrNull()
          ?.takeIf { eventCount -> eventCount >= 0L },
    )
  }

internal fun ManagedProcessObservationCursorState.toDeliveredObservationState(
    snapshot: ManagedProcessSnapshot,
  ):
    ManagedProcessDeliveredObservationState = ManagedProcessDeliveredObservationState(
    mode = mode,
    cursor = cursor,
    stdoutBytes = stdoutBytes,
    stderrBytes = stderrBytes,
    providerMode = providerMode ?: snapshot.normalizedObservationState()?.providerMode,
    providerCursor = providerCursor ?: snapshot.normalizedObservationState()?.providerCursor,
    providerEventCount = providerEventCount ?: snapshot.normalizedObservationState()?.providerEventCount,
    deliveredAtEpochMs = System.currentTimeMillis(),
  )

private fun OpenCrayToolDispatcher.managedProcessProviderObservationBoundary(
    current: ManagedProcessObservationCursorState,
    previous: ManagedProcessObservationCursorState,
  ): ManagedProcessProviderObservationBoundary? {
    val currentProviderCursor = current.providerCursor?.trim()?.takeIf(String::isNotBlank) ?: return null
    val previousProviderCursor = previous.providerCursor?.trim()?.takeIf(String::isNotBlank) ?: return null
    return ManagedProcessProviderObservationBoundary(
      cursorBefore = previousProviderCursor,
      cursorAfter = currentProviderCursor,
      eventCountBefore = previous.providerEventCount,
      eventCountAfter = current.providerEventCount,
    )
  }

private fun OpenCrayToolDispatcher.managedProcessProviderObservationResetWarning(
    current: ManagedProcessObservationCursorState,
    previous: ManagedProcessObservationCursorState,
  ): String? {
    val currentEventCount = current.providerEventCount ?: return null
    val previousEventCount = previous.providerEventCount ?: return null
    if (currentEventCount < previousEventCount) {
      return "provider observation cursor regressed; returning full snapshot output"
    }
    if (
      currentEventCount == previousEventCount &&
      (
        current.stdoutBytes > previous.stdoutBytes ||
          current.stderrBytes > previous.stderrBytes
        )
    ) {
      return "provider observation cursor did not advance while output changed; returning full snapshot output"
    }
    return null
  }

internal fun OpenCrayToolDispatcher.utf8DeltaFromByteOffset(
    text: String,
    byteOffset: Long,
  ): String? {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    if (byteOffset < 0L) {
      return null
    }
    if (byteOffset > bytes.size.toLong()) {
      return null
    }
    return try {
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, byteOffset.toInt(), bytes.size - byteOffset.toInt()))
        .toString()
    } catch (_: CharacterCodingException) {
      null
    }
  }

internal fun String.toUtf8Length(): Long = toByteArray(StandardCharsets.UTF_8).size.toLong()

internal fun StringBuilder.appendManagedProcessMetadataLine(
    snapshot: ManagedProcessSnapshot,
    metadataKey: String,
    renderedKey: String,
  ) {
    snapshot.metadata[metadataKey]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { value -> appendLine("$renderedKey=$value") }
  }
