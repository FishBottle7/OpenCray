package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.InMemoryAgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessDeliveredObservationState
import com.opencray.runtime.process.ManagedProcessObservationState
import com.opencray.runtime.process.ManagedProcessReconnectSeed
import com.opencray.runtime.process.ManagedProcessReconnectState
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.process.RoutedManagedProcessControllerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentManagedProcessToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun safeModeProcessStartRequiresHighRiskApprovalBeforeSpawn() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-safe").toPath()
    val registry = RecordingProcessRegistry(workspaceRoot = workspaceRoot)
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "SAFE")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("HIGH_RISK", result.metadata["approvalRisk"])
    assertEquals("execution", result.metadata["intentCategory"])
    assertEquals("managed_command", result.metadata["executionIntentKind"])
    assertEquals("managed_process", result.metadata["executionTransport"])
    assertEquals("npm", result.metadata["executionCommandPreview"])
    assertEquals(".", result.metadata["executionWorkingDirectory"])
    assertEquals(0, registry.startCount)
  }

  @Test
  fun safeModeBashRequiresHighRiskApprovalBeforeSpawn() {
    val workspaceRoot = temporaryFolder.newFolder("bash-tool-safe").toPath()
    val registry = RecordingProcessRegistry(workspaceRoot = workspaceRoot)
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "SAFE")),
      call = AgentToolCall(
        toolName = "Bash",
        arguments = JsonObject(
          mapOf("command" to JsonPrimitive("Get-ChildItem")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("HIGH_RISK", result.metadata["approvalRisk"])
    assertEquals("execution", result.metadata["intentCategory"])
    assertEquals("shell_command", result.metadata["executionIntentKind"])
    assertEquals("managed_process", result.metadata["executionTransport"])
    assertEquals("Get-ChildItem", result.metadata["executionCommandPreview"])
    assertEquals(".", result.metadata["executionWorkingDirectory"])
    assertEquals(0, registry.startCount)
  }

  @Test
  fun developerModeManagedProcessToolsCanStartReadWaitListAndTerminate() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-developer").toPath()
    val registry = RecordingProcessRegistry(workspaceRoot = workspaceRoot)
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    val readResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )
    val waitResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessWait",
        arguments = JsonObject(
          mapOf(
            "process_id" to JsonPrimitive(processId),
            "timeout_ms" to JsonPrimitive(250),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val listResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(toolName = "ProcessList"),
      hooks = runtimeHooks(),
    )
    val terminateResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessTerminate",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, startResult.status)
    assertEquals(1, registry.startCount)
    assertEquals("ALLOW_DEVELOPER_OVERRIDE", startResult.metadata["policyReasonCode"])
    assertEquals("execute_command", startResult.metadata["capabilityKind"])
    assertEquals("working_directory", startResult.metadata["targetKind"])
    assertEquals("inside_workspace", startResult.metadata["workspaceRelation"])
    assertEquals(".", startResult.metadata["primaryTargetPath"])
    assertEquals("npm", startResult.metadata["targetSummary"])
    assertEquals("execution", startResult.metadata["intentCategory"])
    assertEquals("managed_command", startResult.metadata["executionIntentKind"])
    assertEquals("managed_process", startResult.metadata["executionTransport"])
    assertEquals("npm", startResult.metadata["executionCommandPreview"])
    assertEquals(".", startResult.metadata["executionWorkingDirectory"])
    assertEquals("RUNNING", startResult.metadata["processStatus"])
    assertTrue(startResult.content.contains("process_id=$processId"))
    assertTrue(readResult.content.contains("status=running"))
    assertEquals("true", readResult.metadata["resultLimitApplied"])
    assertEquals("false", readResult.metadata["resultTruncated"])
    assertEquals("process_output_byte_limit", readResult.metadata["resultLimitKind"])
    assertEquals("server ready", registry.waitSnapshots.single().stdout)
    assertTrue(waitResult.content.contains("status=success"))
    assertTrue(waitResult.content.contains("[stdout]"))
    assertTrue(waitResult.content.contains("server ready"))
    assertEquals(0, waitResult.exitCode)
    assertEquals("server ready", waitResult.stdout.trim())
    assertEquals("", waitResult.stderr)
    assertEquals("true", waitResult.metadata["resultLimitApplied"])
    assertEquals("false", waitResult.metadata["resultTruncated"])
    assertEquals("process_output_byte_limit", waitResult.metadata["resultLimitKind"])
    assertTrue(listResult.content.contains(processId))
    assertEquals("ALLOW_DEVELOPER_OVERRIDE", terminateResult.metadata["policyReasonCode"])
    assertEquals("process_lifecycle", terminateResult.metadata["intentCategory"])
    assertEquals("terminate", terminateResult.metadata["processLifecycleIntentKind"])
    assertEquals(processId, terminateResult.metadata["intentProcessId"])
    assertEquals(".", terminateResult.metadata["intentWorkingDirectory"])
    assertTrue(terminateResult.content.contains("process_id=$processId"))
    assertEquals(137, terminateResult.exitCode)
    assertEquals("CANCELLED", terminateResult.errorCode)
    assertEquals("Managed process terminated.", terminateResult.errorMessage)
    assertEquals(1, registry.terminateCount)
  }

  @Test
  fun processReadAndWaitRenderSandboxBackendMetadataForHostedNativeSnapshots() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-native-render").toPath()
    val registry = RecordingProcessRegistry(
      workspaceRoot = workspaceRoot,
      startedSnapshotMetadata = mapOf(
        "runtimeBackend" to "e2b_envd_native_command",
        "runtimeTransport" to "connect_proto_minimal",
        "sandboxCommandBackendKind" to "provider_native",
        "sandboxCommandBackendResolvedKind" to "provider_native",
        "sandboxCommandProviderNative" to "true",
        "sandboxCommandSupportsStreamingLogs" to "false",
        "sandboxCommandSupportsReconnect" to "true",
        "sandboxCommandSupportsManagedProcessLiveObservation" to "true",
        "sandboxCommandSupportsManagedProcessObservationCursorResume" to "false",
        "sandboxCommandSupportsManagedProcessObservationBackfill" to "false",
        "sandboxCommandProviderObservationResumeContract" to "host_buffered_seed_then_live_attach",
        "sandboxCommandProviderObservationResumeBlocker" to "envd_connect_request_selector_only",
        "sandboxCommandProviderHandleKind" to "envd_process",
        "sandboxCommandProviderStableSelectorKind" to "tag",
        "sandboxCommandProviderStableSelectorValue" to "proc-native-render",
        "sandboxCommandProviderLiveSelectorKind" to "pid",
        "sandboxCommandProviderLiveSelectorValue" to "654",
        "sandboxCommandIdKind" to "tag",
        "sandboxCommandId" to "proc-native-render",
        "sandboxCommandProviderObservationMode" to "provider_event_stream_host_buffered",
        "sandboxCommandProviderObservationEventCount" to "3",
        "sandboxCommandProviderObservationCursor" to "envd_seq_3",
        "sandboxCommandProviderObservationBackfillSupported" to "false",
        "sandboxCommandHandleIdKind" to "tag",
        "sandboxCommandHandleId" to "proc-native-render",
        "sandboxCommandHandleTag" to "proc-native-render",
        "sandboxCommandObservationMode" to "host_managed_snapshot",
        "sandboxCommandObservationEventCount" to "3",
        "sandboxCommandObservationCursor" to "host_seq_3",
        "sandboxCommandObservationStdoutBytes" to "23",
        "sandboxCommandObservationStderrBytes" to "0",
        "sandboxCommandApi" to "envd_process_start",
        "sandboxCommandReconnectApi" to "envd_process_connect",
        "sandboxCommandReconnectStatus" to "attached",
        "sandboxCommandReconnectRecoveryState" to "attached_live",
        "sandboxCommandReconnectSource" to "durable_registry_restore",
        "sandboxCommandReconnectResumeMode" to "seed_snapshot_then_live_attach",
        "sandboxCommandReconnectBackfillSupported" to "false",
        "sandboxCommandReconnectOutputGapRisk" to "true",
        "sandboxCommandReconnectRetryable" to "false",
        "sandboxCommandReconnectAttemptCount" to "2",
        "sandboxCommandReconnectSelectorKind" to "pid",
        "sandboxCommandReconnectSelectorValue" to "654",
        "sandboxCommandReconnectSelectorSource" to "snapshot_pid",
        "sandboxCommandReconnectLastAttachedAtEpochMs" to "1500",
        "sandboxCommandReconnectLastEventAtEpochMs" to "1600",
        "sandboxCommandReconnectLastEventKind" to "data",
        "sandboxCommandReconnectSeedSource" to "durable_snapshot_metadata",
        "sandboxCommandReconnectProviderObservationSeedConsumed" to "true",
        "sandboxCommandReconnectProviderObservationSeedState" to "consumed_live_attach",
        "sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs" to "1450",
        "sandboxCommandReconnectSeedObservationCursor" to "host_seq_1",
        "sandboxCommandReconnectSeedProviderObservationCursor" to "envd_seq_1",
        "sandboxCommandReconnectSeedEventCount" to "1",
        "sandboxCommandReconnectSeedProviderObservationEventCount" to "1",
        "sandboxCommandReconnectSeededStdoutBytes" to "7",
        "sandboxCommandReconnectSeededStderrBytes" to "0",
        "sandboxCommandNativeProtocol" to "envd_connect_process_v1",
        "sandboxCommandSessionSource" to "persisted",
        "sandboxCommandPid" to "654",
      ),
      waitedSnapshotMetadata = mapOf(
        "sandboxCommandReconnectStatus" to "completed",
        "sandboxCommandReconnectRecoveryState" to "completed",
        "sandboxCommandReconnectHttpStatusCode" to "200",
      ),
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    assertTrue(startResult.content.contains("sandbox_command_provider_handle_kind=envd_process"))
    assertTrue(startResult.content.contains("sandbox_command_provider_stable_selector_kind=tag"))
    assertTrue(startResult.content.contains("sandbox_command_provider_live_selector_kind=pid"))
    assertTrue(startResult.content.contains("sandbox_command_id_kind=tag"))
    assertTrue(startResult.content.contains("sandbox_command_id=proc-native-render"))
    assertEquals("envd_process", startResult.metadata["sandboxCommandProviderHandleKind"])
    assertEquals("tag", startResult.metadata["sandboxCommandProviderStableSelectorKind"])
    assertEquals("654", startResult.metadata["sandboxCommandProviderLiveSelectorValue"])
    assertEquals("tag", startResult.metadata["sandboxCommandIdKind"])
    assertEquals("proc-native-render", startResult.metadata["sandboxCommandId"])

    val readResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )
    val waitResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessWait",
        arguments = JsonObject(
          mapOf(
            "process_id" to JsonPrimitive(processId),
            "timeout_ms" to JsonPrimitive(250),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(readResult.content.contains("runtime_backend=e2b_envd_native_command"))
    assertTrue(readResult.content.contains("runtime_transport=connect_proto_minimal"))
    assertTrue(readResult.content.contains("sandbox_backend_resolved_kind=provider_native"))
    assertTrue(readResult.content.contains("sandbox_supports_reconnect=true"))
    assertTrue(readResult.content.contains("sandbox_supports_managed_process_live_observation=true"))
    assertTrue(
      readResult.content.contains(
        "sandbox_supports_managed_process_observation_cursor_resume=false",
      ),
    )
    assertTrue(
      readResult.content.contains(
        "sandbox_supports_managed_process_observation_backfill=false",
      ),
    )
    assertTrue(readResult.content.contains("sandbox_command_provider_handle_kind=envd_process"))
    assertTrue(readResult.content.contains("sandbox_command_provider_stable_selector_kind=tag"))
    assertTrue(readResult.content.contains("sandbox_command_provider_stable_selector_value=proc-native-render"))
    assertTrue(readResult.content.contains("sandbox_command_provider_live_selector_kind=pid"))
    assertTrue(readResult.content.contains("sandbox_command_provider_live_selector_value=654"))
    assertTrue(readResult.content.contains("sandbox_command_id_kind=tag"))
    assertTrue(readResult.content.contains("sandbox_command_id=proc-native-render"))
    assertTrue(
      readResult.content.contains(
        "sandbox_command_provider_observation_mode=provider_event_stream_host_buffered",
      ),
    )
    assertTrue(readResult.content.contains("sandbox_command_provider_observation_event_count=3"))
    assertTrue(readResult.content.contains("sandbox_command_provider_observation_cursor=envd_seq_3"))
    assertTrue(readResult.content.contains("sandbox_command_provider_observation_backfill_supported=false"))
    assertTrue(
      readResult.content.contains(
        "sandbox_command_provider_observation_resume_contract=host_buffered_seed_then_live_attach",
      ),
    )
    assertTrue(
      readResult.content.contains(
        "sandbox_command_provider_observation_resume_blocker=envd_connect_request_selector_only",
      ),
    )
    assertTrue(readResult.content.contains("sandbox_command_handle_id_kind=tag"))
    assertTrue(readResult.content.contains("sandbox_command_handle_id=proc-native-render"))
    assertTrue(readResult.content.contains("sandbox_command_handle_tag=proc-native-render"))
    assertTrue(readResult.content.contains("sandbox_observation_mode=host_managed_snapshot"))
    assertTrue(readResult.content.contains("sandbox_command_observation_event_count=3"))
    assertTrue(readResult.content.contains("sandbox_command_observation_cursor=host_seq_3"))
    assertTrue(readResult.content.contains("sandbox_command_observation_stdout_bytes=23"))
    assertTrue(readResult.content.contains("sandbox_command_observation_stderr_bytes=0"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_api=envd_process_connect"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_status=attached"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_recovery_state=attached_live"))
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_resume_mode=seed_snapshot_then_live_attach",
      ),
    )
    assertTrue(readResult.content.contains("sandbox_command_reconnect_backfill_supported=false"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_output_gap_risk=true"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_retryable=false"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_attempt_count=2"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_selector_kind=pid"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_selector_value=654"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_selector_source=snapshot_pid"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_seed_source=durable_snapshot_metadata"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_provider_observation_seed_consumed=true"))
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_provider_observation_seed_state=consumed_live_attach",
      ),
    )
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_provider_observation_seed_consumed_at_epoch_ms=1450",
      ),
    )
    assertTrue(readResult.content.contains("sandbox_command_reconnect_last_attached_at_epoch_ms=1500"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_last_event_at_epoch_ms=1600"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_last_event_kind=data"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_seed_observation_cursor=host_seq_1"))
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_seed_provider_observation_cursor=envd_seq_1",
      ),
    )
    assertTrue(readResult.content.contains("sandbox_command_reconnect_seed_event_count=1"))
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_seed_provider_observation_event_count=1",
      ),
    )
    assertTrue(readResult.content.contains("sandbox_command_reconnect_seeded_stdout_bytes=7"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_seeded_stderr_bytes=0"))
    assertTrue(
      readResult.content.contains(
        "observation_warning=provider reconnect resumed from persisted snapshot without log backfill; output emitted before attach may be missing",
      ),
    )
    assertTrue(readResult.content.contains("sandbox_command_pid=654"))
    assertEquals("e2b_envd_native_command", readResult.metadata["runtimeBackend"])
    assertEquals("host_managed_snapshot", readResult.metadata["sandboxCommandObservationMode"])
    assertEquals("envd_process", readResult.metadata["sandboxCommandProviderHandleKind"])
    assertEquals("tag", readResult.metadata["sandboxCommandIdKind"])
    assertEquals("proc-native-render", readResult.metadata["sandboxCommandId"])
    assertEquals(
      "provider_event_stream_host_buffered",
      readResult.metadata["sandboxCommandProviderObservationMode"],
    )
    assertEquals("envd_seq_3", readResult.metadata["sandboxCommandProviderObservationCursor"])
    assertEquals(
      "host_buffered_seed_then_live_attach",
      readResult.metadata["sandboxCommandProviderObservationResumeContract"],
    )
    assertEquals(
      "envd_connect_request_selector_only",
      readResult.metadata["sandboxCommandProviderObservationResumeBlocker"],
    )
    assertEquals("proc-native-render", readResult.metadata["sandboxCommandHandleId"])
    assertEquals("host_seq_3", readResult.metadata["sandboxCommandObservationCursor"])
    assertEquals("true", readResult.metadata["sandboxCommandSupportsReconnect"])
    assertEquals("true", readResult.metadata["sandboxCommandSupportsManagedProcessLiveObservation"])
    assertEquals(
      "false",
      readResult.metadata["sandboxCommandSupportsManagedProcessObservationCursorResume"],
    )
    assertEquals(
      "false",
      readResult.metadata["sandboxCommandSupportsManagedProcessObservationBackfill"],
    )
    assertEquals(
      "seed_snapshot_then_live_attach",
      readResult.metadata["sandboxCommandReconnectResumeMode"],
    )
    assertEquals("attached_live", readResult.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("true", readResult.metadata["sandboxCommandReconnectOutputGapRisk"])
    assertEquals("1500", readResult.metadata["sandboxCommandReconnectLastAttachedAtEpochMs"])
    assertEquals("1600", readResult.metadata["sandboxCommandReconnectLastEventAtEpochMs"])
    assertEquals("data", readResult.metadata["sandboxCommandReconnectLastEventKind"])
    assertEquals("durable_snapshot_metadata", readResult.metadata["sandboxCommandReconnectSeedSource"])
    assertEquals("true", readResult.metadata["sandboxCommandReconnectProviderObservationSeedConsumed"])
    assertEquals(
      "consumed_live_attach",
      readResult.metadata["sandboxCommandReconnectProviderObservationSeedState"],
    )
    assertEquals(
      "1450",
      readResult.metadata["sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs"],
    )
    assertEquals("pid", readResult.metadata["sandboxCommandReconnectSelectorKind"])
    assertEquals("654", readResult.metadata["sandboxCommandReconnectSelectorValue"])
    assertEquals("host_seq_1", readResult.metadata["sandboxCommandReconnectSeedObservationCursor"])
    assertEquals(
      "envd_seq_1",
      readResult.metadata["sandboxCommandReconnectSeedProviderObservationCursor"],
    )
    assertEquals("1", readResult.metadata["sandboxCommandReconnectSeedEventCount"])
    assertEquals(
      "1",
      readResult.metadata["sandboxCommandReconnectSeedProviderObservationEventCount"],
    )

    assertTrue(waitResult.content.contains("runtime_backend=e2b_envd_native_command"))
    assertTrue(waitResult.content.contains("sandbox_command_api=envd_process_start"))
    assertTrue(waitResult.content.contains("sandbox_command_native_protocol=envd_connect_process_v1"))
    assertTrue(waitResult.content.contains("sandbox_command_session_source=persisted"))
    assertTrue(waitResult.content.contains("sandbox_command_observation_cursor=host_seq_3"))
    assertTrue(waitResult.content.contains("sandbox_command_reconnect_recovery_state=completed"))
    assertTrue(waitResult.content.contains("sandbox_command_reconnect_output_gap_risk=true"))
    assertTrue(waitResult.content.contains("status=success"))
    assertEquals("provider_native", waitResult.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals("completed", waitResult.metadata["sandboxCommandReconnectRecoveryState"])
  }

  @Test
  fun processReadRendersRetryableReconnectWarningForRunningNativeSnapshot() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-native-retryable-render").toPath()
    val registry = RecordingProcessRegistry(
      workspaceRoot = workspaceRoot,
      startedSnapshotMetadata = mapOf(
        "runtimeBackend" to "e2b_envd_native_command",
        "runtimeTransport" to "connect_proto_minimal",
        "sandboxCommandBackendKind" to "provider_native",
        "sandboxCommandBackendResolvedKind" to "provider_native",
        "sandboxCommandProviderNative" to "true",
        "sandboxCommandSupportsStreamingLogs" to "false",
        "sandboxCommandSupportsReconnect" to "true",
        "sandboxCommandObservationMode" to "host_managed_snapshot",
        "sandboxCommandReconnectApi" to "envd_process_connect",
        "sandboxCommandReconnectStatus" to "retryable_failure",
        "sandboxCommandReconnectRecoveryState" to "retry_scheduled",
        "sandboxCommandReconnectSource" to "durable_registry_restore",
        "sandboxCommandReconnectOutputGapRisk" to "true",
        "sandboxCommandReconnectRetryable" to "true",
        "sandboxCommandReconnectRetryAfterEpochMs" to "2200",
        "sandboxCommandReconnectAttemptCount" to "1",
        "sandboxCommandReconnectFailureStage" to "transport_exception_before_live_attach",
        "sandboxCommandReconnectSeedSource" to "durable_snapshot_metadata",
        "sandboxCommandReconnectProviderObservationSeedConsumed" to "false",
        "sandboxCommandReconnectProviderObservationSeedState" to "retry_scheduled_before_live_attach",
        "sandboxCommandReconnectProviderObservationSeedSource" to
          "durable_delivered_observation_state",
        "sandboxCommandReconnectProviderObservationResumeApplied" to "false",
        "sandboxCommandReconnectProviderObservationResumeReason" to
          "protocol_cursor_resume_unsupported",
        "sandboxCommandReconnectLastFailureAtEpochMs" to "1200",
      ),
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    val readResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(readResult.content.contains("sandbox_command_reconnect_status=retryable_failure"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_recovery_state=retry_scheduled"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_retryable=true"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_retry_after_epoch_ms=2200"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_attempt_count=1"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_last_failure_at_epoch_ms=1200"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_seed_source=durable_snapshot_metadata"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_provider_observation_seed_consumed=false"))
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_provider_observation_seed_state=retry_scheduled_before_live_attach",
      ),
    )
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_provider_observation_seed_source=durable_delivered_observation_state",
      ),
    )
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_provider_observation_resume_applied=false",
      ),
    )
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_provider_observation_resume_reason=protocol_cursor_resume_unsupported",
      ),
    )
    assertTrue(
      readResult.content.contains(
        "observation_warning=provider reconnect has not yet reattached live output; current output still reflects the persisted host snapshot seed and a later ProcessRead or ProcessWait may retry attach after backoff",
      ),
    )
    assertFalse(
      readResult.content.contains(
        "observation_warning=provider reconnect resumed from persisted snapshot without log backfill; output emitted before attach may be missing",
      ),
    )
    assertEquals("true", readResult.metadata["sandboxCommandReconnectRetryable"])
    assertEquals("retry_scheduled", readResult.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("1200", readResult.metadata["sandboxCommandReconnectLastFailureAtEpochMs"])
    assertEquals("durable_snapshot_metadata", readResult.metadata["sandboxCommandReconnectSeedSource"])
    assertEquals("false", readResult.metadata["sandboxCommandReconnectProviderObservationSeedConsumed"])
    assertEquals(
      "retry_scheduled_before_live_attach",
      readResult.metadata["sandboxCommandReconnectProviderObservationSeedState"],
    )
    assertEquals(
      "durable_delivered_observation_state",
      readResult.metadata["sandboxCommandReconnectProviderObservationSeedSource"],
    )
    assertEquals(
      "false",
      readResult.metadata["sandboxCommandReconnectProviderObservationResumeApplied"],
    )
    assertEquals(
      "protocol_cursor_resume_unsupported",
      readResult.metadata["sandboxCommandReconnectProviderObservationResumeReason"],
    )
  }

  @Test
  fun processReadRendersPendingLiveAttachWarningForNativeSnapshot() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-native-pending-live-attach-render").toPath()
    val registry = RecordingProcessRegistry(
      workspaceRoot = workspaceRoot,
      startedSnapshotMetadata = mapOf(
        "runtimeBackend" to "e2b_envd_native_command",
        "runtimeTransport" to "connect_proto_minimal",
        "sandboxCommandBackendKind" to "provider_native",
        "sandboxCommandBackendResolvedKind" to "provider_native",
        "sandboxCommandProviderNative" to "true",
        "sandboxCommandSupportsStreamingLogs" to "false",
        "sandboxCommandSupportsReconnect" to "true",
        "sandboxCommandObservationMode" to "host_managed_snapshot",
        "sandboxCommandReconnectApi" to "envd_process_connect",
        "sandboxCommandReconnectStatus" to "connecting",
        "sandboxCommandReconnectRecoveryState" to "connecting",
        "sandboxCommandReconnectSource" to "durable_registry_restore",
        "sandboxCommandReconnectOutputGapRisk" to "true",
        "sandboxCommandReconnectRetryable" to "false",
        "sandboxCommandReconnectSeedSource" to "durable_snapshot_metadata",
        "sandboxCommandReconnectProviderObservationSeedConsumed" to "false",
        "sandboxCommandReconnectProviderObservationSeedState" to "pending_live_attach",
      ),
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    val readResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(readResult.content.contains("sandbox_command_reconnect_status=connecting"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_recovery_state=connecting"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_seed_source=durable_snapshot_metadata"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_provider_observation_seed_consumed=false"))
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_provider_observation_seed_state=pending_live_attach",
      ),
    )
    assertTrue(
      readResult.content.contains(
        "observation_warning=provider reconnect restored a persisted output seed and is still waiting for live attach; current output may only reflect the persisted host snapshot",
      ),
    )
    assertFalse(
      readResult.content.contains(
        "observation_warning=provider reconnect resumed from persisted snapshot without log backfill; output emitted before attach may be missing",
      ),
    )
    assertEquals("pending_live_attach", readResult.metadata["sandboxCommandReconnectProviderObservationSeedState"])
  }

  @Test
  fun processReadRendersTerminalReconnectFailureWarningForNativeSnapshot() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-native-terminal-failure-render").toPath()
    val registry = RecordingProcessRegistry(
      workspaceRoot = workspaceRoot,
      startedSnapshotStatus = ManagedProcessStatus.FAILED,
      startedSnapshotErrorCode = "PROCESS_RECONNECT_FAILED",
      startedSnapshotErrorMessage = "Managed sandbox command reconnect returned HTTP 404.",
      startedSnapshotMetadata = mapOf(
        "runtimeBackend" to "e2b_envd_native_command",
        "runtimeTransport" to "connect_proto_minimal",
        "sandboxCommandBackendKind" to "provider_native",
        "sandboxCommandBackendResolvedKind" to "provider_native",
        "sandboxCommandProviderNative" to "true",
        "sandboxCommandSupportsStreamingLogs" to "false",
        "sandboxCommandSupportsReconnect" to "true",
        "sandboxCommandObservationMode" to "host_managed_snapshot",
        "sandboxCommandReconnectApi" to "envd_process_connect",
        "sandboxCommandReconnectStatus" to "failed",
        "sandboxCommandReconnectRecoveryState" to "failed_terminal",
        "sandboxCommandReconnectSource" to "durable_registry_restore",
        "sandboxCommandReconnectRetryable" to "false",
        "sandboxCommandReconnectFailureStage" to "http_response_non_success",
        "sandboxCommandReconnectHttpStatusCode" to "404",
        "sandboxCommandReconnectSeedSource" to "durable_snapshot_metadata",
        "sandboxCommandReconnectProviderObservationSeedConsumed" to "false",
        "sandboxCommandReconnectProviderObservationSeedState" to "failed_terminal_before_live_attach",
      ),
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    val readResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(readResult.content.contains("status=failed"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_status=failed"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_recovery_state=failed_terminal"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_http_status_code=404"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_seed_source=durable_snapshot_metadata"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_provider_observation_seed_consumed=false"))
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_provider_observation_seed_state=failed_terminal_before_live_attach",
      ),
    )
    assertTrue(
      readResult.content.contains(
        "observation_warning=provider reconnect terminated before live attach; current output may only reflect the persisted host snapshot",
      ),
    )
    assertEquals("failed_terminal", readResult.metadata["sandboxCommandReconnectRecoveryState"])
  }

  @Test
  fun firstProcessReadUsesReconnectSeedAsInitialObservationBaseline() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-native-seed-baseline-read").toPath()
    val registry = SequencedObservationProcessRegistry(
      workspaceRoot = workspaceRoot,
      startedPlan = observationPlan(
        stdout = "booting\nready",
        cursor = "host_seq_2",
        metadata = reconnectSeedMetadata(
          cursor = "host_seq_1",
          stdoutBytes = 8L,
        ),
      ),
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    val readResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(readResult.content.contains("sandbox_command_observation_delivery_mode=delta"))
    assertTrue(readResult.content.contains("sandbox_command_observation_cursor_before=host_seq_1"))
    assertTrue(readResult.content.contains("sandbox_command_observation_cursor_after=host_seq_2"))
    assertTrue(readResult.content.contains("sandbox_command_observation_stdout_delta_bytes=5"))
    assertTrue(readResult.content.contains("[stdout]"))
    assertTrue(readResult.content.contains("ready"))
    assertFalse(readResult.content.contains("[stdout]\nbooting"))
    assertEquals("ready", readResult.stdout.trim())
    assertEquals("delta", readResult.metadata["sandboxCommandObservationDeliveryMode"])
    assertEquals("host_seq_1", readResult.metadata["sandboxCommandObservationCursorBefore"])
    assertEquals("host_seq_2", readResult.metadata["sandboxCommandObservationCursorAfter"])
  }

  @Test
  fun firstProcessReadUsesTypedReconnectSeedWhenObservationMetadataIsSparse() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-native-typed-seed-baseline-read").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = object : AgentProcessRegistry {
          private val snapshotsById = linkedMapOf<String, ManagedProcessSnapshot>()

          override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
            val snapshot = ManagedProcessSnapshot(
              processId = request.processId,
              taskId = request.taskId,
              command = request.command,
              args = request.args,
              workingDirectory = request.workingDirectory,
              status = ManagedProcessStatus.RUNNING,
              processStarted = true,
              timeoutMs = request.timeoutMs,
              stdout = "booting\nready",
              startedAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_000L,
              observationState = ManagedProcessObservationState(
                mode = "host_managed_snapshot",
                hostEventCount = 2L,
                hostCursor = "host_seq_2",
                stdoutBytes = "booting\nready".toByteArray(StandardCharsets.UTF_8).size.toLong(),
                stderrBytes = 0L,
                providerMode = "provider_event_stream_host_buffered",
                providerEventCount = 2L,
                providerCursor = "envd_seq_2",
              ),
              reconnectState = ManagedProcessReconnectState(
                providerObservationResumeApplied = false,
                providerObservationResumeReason = "protocol_cursor_resume_unsupported",
                seed = ManagedProcessReconnectSeed(
                  source = "durable_snapshot_metadata",
                  hostObservationCursor = "host_seq_1",
                  stdoutBytes = 8L,
                  stderrBytes = 0L,
                  providerObservationCursor = "envd_seq_1",
                  providerObservationEventCount = 1L,
                  providerObservationSeedSource = "observation_snapshot_metadata",
                ),
              ),
              metadata = request.metadata,
            )
            snapshotsById[request.processId] = snapshot
            return snapshot
          }

          override fun list(): List<ManagedProcessSnapshot> = snapshotsById.values.toList()

          override fun read(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]

          override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? =
            snapshotsById[processId]

          override fun terminate(processId: String): ManagedProcessSnapshot? =
            snapshotsById[processId]
        },
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    val readResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(readResult.content.contains("sandbox_command_observation_delivery_mode=delta"))
    assertTrue(readResult.content.contains("sandbox_command_observation_cursor_before=host_seq_1"))
    assertTrue(readResult.content.contains("sandbox_command_observation_cursor_after=host_seq_2"))
    assertTrue(readResult.content.contains("sandbox_command_observation_stdout_delta_bytes=5"))
    assertTrue(readResult.content.contains("sandbox_command_provider_observation_cursor_before=envd_seq_1"))
    assertTrue(readResult.content.contains("sandbox_command_provider_observation_cursor_after=envd_seq_2"))
    assertTrue(readResult.content.contains("sandbox_command_provider_observation_event_count_before=1"))
    assertTrue(readResult.content.contains("sandbox_command_provider_observation_event_count_after=2"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_seed_source=durable_snapshot_metadata"))
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_provider_observation_seed_source=observation_snapshot_metadata",
      ),
    )
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_provider_observation_resume_applied=false",
      ),
    )
    assertTrue(
      readResult.content.contains(
        "sandbox_command_reconnect_provider_observation_resume_reason=protocol_cursor_resume_unsupported",
      ),
    )
    assertTrue(readResult.content.contains("[stdout]"))
    assertTrue(readResult.content.contains("ready"))
    assertFalse(readResult.content.contains("[stdout]\nbooting"))
    assertEquals("ready", readResult.stdout.trim())
    assertEquals("delta", readResult.metadata["sandboxCommandObservationDeliveryMode"])
    assertEquals("host_seq_1", readResult.metadata["sandboxCommandObservationCursorBefore"])
    assertEquals("host_seq_2", readResult.metadata["sandboxCommandObservationCursorAfter"])
    assertEquals("envd_seq_1", readResult.metadata["sandboxCommandProviderObservationCursorBefore"])
    assertEquals("envd_seq_2", readResult.metadata["sandboxCommandProviderObservationCursorAfter"])
    assertEquals("1", readResult.metadata["sandboxCommandProviderObservationEventCountBefore"])
    assertEquals("2", readResult.metadata["sandboxCommandProviderObservationEventCountAfter"])
    assertEquals("durable_snapshot_metadata", readResult.metadata["sandboxCommandReconnectSeedSource"])
    assertEquals(
      "observation_snapshot_metadata",
      readResult.metadata["sandboxCommandReconnectProviderObservationSeedSource"],
    )
    assertEquals(
      "false",
      readResult.metadata["sandboxCommandReconnectProviderObservationResumeApplied"],
    )
    assertEquals(
      "protocol_cursor_resume_unsupported",
      readResult.metadata["sandboxCommandReconnectProviderObservationResumeReason"],
    )
  }

  @Test
  fun processReadFallsBackToFullSnapshotWhenProviderObservationCursorRegresses() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-native-provider-cursor-reset").toPath()
    val registry = SequencedObservationProcessRegistry(
      workspaceRoot = workspaceRoot,
      startedPlan = observationPlan(
        stdout = "booting\nready",
        cursor = "host_seq_2",
        providerCursor = "envd_seq_2",
        providerEventCount = 2L,
      ),
      readPlans = mutableListOf(
        observationPlan(
          stdout = "booting\nready\nagain",
          cursor = "host_seq_3",
          providerCursor = "envd_seq_1",
          providerEventCount = 1L,
        ),
      ),
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )
    val regressedRead = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(regressedRead.content.contains("sandbox_command_observation_delivery_mode=reset_full"))
    assertTrue(regressedRead.content.contains("sandbox_command_observation_cursor_before=host_seq_2"))
    assertTrue(regressedRead.content.contains("sandbox_command_observation_cursor_after=host_seq_3"))
    assertTrue(regressedRead.content.contains("sandbox_command_provider_observation_cursor_before=envd_seq_2"))
    assertTrue(regressedRead.content.contains("sandbox_command_provider_observation_cursor_after=envd_seq_1"))
    assertTrue(regressedRead.content.contains("sandbox_command_provider_observation_event_count_before=2"))
    assertTrue(regressedRead.content.contains("sandbox_command_provider_observation_event_count_after=1"))
    assertTrue(
      regressedRead.content.contains(
        "observation_warning=provider observation cursor regressed; returning full snapshot output",
      ),
    )
    assertTrue(regressedRead.content.contains("[stdout]"))
    assertTrue(regressedRead.content.contains("booting"))
    assertTrue(regressedRead.content.contains("again"))
    assertEquals("reset_full", regressedRead.metadata["sandboxCommandObservationDeliveryMode"])
    assertEquals("envd_seq_2", regressedRead.metadata["sandboxCommandProviderObservationCursorBefore"])
    assertEquals("envd_seq_1", regressedRead.metadata["sandboxCommandProviderObservationCursorAfter"])
    assertEquals("2", regressedRead.metadata["sandboxCommandProviderObservationEventCountBefore"])
    assertEquals("1", regressedRead.metadata["sandboxCommandProviderObservationEventCountAfter"])
    assertEquals(
      "provider observation cursor regressed; returning full snapshot output",
      regressedRead.metadata["sandboxCommandObservationDeliveryWarning"],
    )
  }

  @Test
  fun processReadFallsBackToFullSnapshotWhenProviderObservationCursorStallsButOutputGrows() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-native-provider-cursor-stall").toPath()
    val registry = SequencedObservationProcessRegistry(
      workspaceRoot = workspaceRoot,
      startedPlan = observationPlan(
        stdout = "booting\nready",
        cursor = "host_seq_2",
        providerCursor = "envd_seq_2",
        providerEventCount = 2L,
      ),
      readPlans = mutableListOf(
        observationPlan(
          stdout = "booting\nready\nagain",
          cursor = "host_seq_3",
          providerCursor = "envd_seq_2",
          providerEventCount = 2L,
        ),
      ),
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )
    val stalledRead = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(stalledRead.content.contains("sandbox_command_observation_delivery_mode=reset_full"))
    assertTrue(stalledRead.content.contains("sandbox_command_observation_cursor_before=host_seq_2"))
    assertTrue(stalledRead.content.contains("sandbox_command_observation_cursor_after=host_seq_3"))
    assertTrue(stalledRead.content.contains("sandbox_command_provider_observation_cursor_before=envd_seq_2"))
    assertTrue(stalledRead.content.contains("sandbox_command_provider_observation_cursor_after=envd_seq_2"))
    assertTrue(stalledRead.content.contains("sandbox_command_provider_observation_event_count_before=2"))
    assertTrue(stalledRead.content.contains("sandbox_command_provider_observation_event_count_after=2"))
    assertTrue(
      stalledRead.content.contains(
        "observation_warning=provider observation cursor did not advance while output changed; returning full snapshot output",
      ),
    )
    assertTrue(stalledRead.content.contains("[stdout]"))
    assertTrue(stalledRead.content.contains("booting"))
    assertTrue(stalledRead.content.contains("again"))
    assertEquals("reset_full", stalledRead.metadata["sandboxCommandObservationDeliveryMode"])
    assertEquals("envd_seq_2", stalledRead.metadata["sandboxCommandProviderObservationCursorBefore"])
    assertEquals("envd_seq_2", stalledRead.metadata["sandboxCommandProviderObservationCursorAfter"])
    assertEquals("2", stalledRead.metadata["sandboxCommandProviderObservationEventCountBefore"])
    assertEquals("2", stalledRead.metadata["sandboxCommandProviderObservationEventCountAfter"])
    assertEquals(
      "provider observation cursor did not advance while output changed; returning full snapshot output",
      stalledRead.metadata["sandboxCommandObservationDeliveryWarning"],
    )
  }

  @Test
  fun firstProcessReadUsesPersistedDeliveredObservationStateWhenSessionTrackerIsEmpty() {
    val workspaceRoot =
      temporaryFolder.newFolder("process-tool-native-persisted-delivery-baseline-read").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = object : AgentProcessRegistry {
          private val snapshotsById = linkedMapOf<String, ManagedProcessSnapshot>()

          override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
            val snapshot = ManagedProcessSnapshot(
              processId = request.processId,
              taskId = request.taskId,
              command = request.command,
              args = request.args,
              workingDirectory = request.workingDirectory,
              status = ManagedProcessStatus.RUNNING,
              processStarted = true,
              timeoutMs = request.timeoutMs,
              stdout = "booting\nready",
              startedAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_000L,
              observationState = ManagedProcessObservationState(
                mode = "host_managed_snapshot",
                hostEventCount = 2L,
                hostCursor = "host_seq_2",
                stdoutBytes = "booting\nready".toByteArray(StandardCharsets.UTF_8).size.toLong(),
                stderrBytes = 0L,
              ),
              deliveredObservationState = ManagedProcessDeliveredObservationState(
                mode = "host_managed_snapshot",
                cursor = "host_seq_1",
                stdoutBytes = 8L,
                stderrBytes = 0L,
                deliveredAtEpochMs = 900L,
              ),
              metadata = request.metadata,
            )
            snapshotsById[request.processId] = snapshot
            return snapshot
          }

          override fun list(): List<ManagedProcessSnapshot> = snapshotsById.values.toList()

          override fun read(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]

          override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? =
            snapshotsById[processId]

          override fun terminate(processId: String): ManagedProcessSnapshot? =
            snapshotsById[processId]
        },
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    val readResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(readResult.content.contains("sandbox_command_observation_delivery_mode=delta"))
    assertTrue(readResult.content.contains("sandbox_command_observation_cursor_before=host_seq_1"))
    assertTrue(readResult.content.contains("sandbox_command_observation_cursor_after=host_seq_2"))
    assertTrue(readResult.content.contains("sandbox_command_observation_stdout_delta_bytes=5"))
    assertEquals("ready", readResult.stdout.trim())
  }

  @Test
  fun processReadPersistsDeliveredObservationStateForFutureRestore() {
    val workspaceRoot =
      temporaryFolder.newFolder("process-tool-native-persisted-delivery-writeback").toPath()
    val recordedStates = mutableListOf<ManagedProcessDeliveredObservationState?>()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = object : AgentProcessRegistry {
          private val snapshotsById = linkedMapOf<String, ManagedProcessSnapshot>()

          override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
            val snapshot = ManagedProcessSnapshot(
              processId = request.processId,
              taskId = request.taskId,
              command = request.command,
              args = request.args,
              workingDirectory = request.workingDirectory,
              status = ManagedProcessStatus.RUNNING,
              processStarted = true,
              timeoutMs = request.timeoutMs,
              stdout = "booting\nready",
              startedAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_000L,
              observationState = ManagedProcessObservationState(
                mode = "host_managed_snapshot",
                hostEventCount = 2L,
                hostCursor = "host_seq_2",
                stdoutBytes = "booting\nready".toByteArray(StandardCharsets.UTF_8).size.toLong(),
                stderrBytes = 0L,
                providerMode = "provider_event_stream_host_buffered",
                providerEventCount = 2L,
                providerCursor = "envd_seq_2",
              ),
              metadata = request.metadata,
            )
            snapshotsById[request.processId] = snapshot
            return snapshot
          }

          override fun list(): List<ManagedProcessSnapshot> = snapshotsById.values.toList()

          override fun read(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]

          override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? =
            snapshotsById[processId]

          override fun terminate(processId: String): ManagedProcessSnapshot? =
            snapshotsById[processId]

          override fun recordObservationDelivery(
            processId: String,
            deliveredObservationState: ManagedProcessDeliveredObservationState?,
          ) {
            recordedStates += deliveredObservationState
          }
        },
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    val recordedState = requireNotNull(recordedStates.single())
    assertEquals("host_managed_snapshot", recordedState.mode)
    assertEquals("host_seq_2", recordedState.cursor)
    assertEquals(
      "booting\nready".toByteArray(StandardCharsets.UTF_8).size.toLong(),
      recordedState.stdoutBytes,
    )
    assertEquals(0L, recordedState.stderrBytes)
    assertEquals("provider_event_stream_host_buffered", recordedState.providerMode)
    assertEquals("envd_seq_2", recordedState.providerCursor)
    assertEquals(2L, recordedState.providerEventCount)
    assertNotNull(recordedState.deliveredAtEpochMs)
  }

  @Test
  fun firstProcessWaitReturnsNoChangeWhenReconnectSeedAlreadyMatchesCurrentOutputWindow() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-native-seed-baseline-wait").toPath()
    val registry = SequencedObservationProcessRegistry(
      workspaceRoot = workspaceRoot,
      startedPlan = observationPlan(
        stdout = "booting",
        cursor = "host_seq_2",
        metadata = reconnectSeedMetadata(
          cursor = "host_seq_1",
          stdoutBytes = 7L,
        ),
      ),
      waitPlans = mutableListOf(
        observationPlan(
          status = ManagedProcessStatus.SUCCESS,
          stdout = "booting",
          cursor = "host_seq_2",
          exitCode = 0,
          finishedAtEpochMs = 1_250L,
        ),
      ),
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    val waitResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessWait",
        arguments = JsonObject(
          mapOf(
            "process_id" to JsonPrimitive(processId),
            "timeout_ms" to JsonPrimitive(250),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(waitResult.content.contains("status=success"))
    assertTrue(waitResult.content.contains("sandbox_command_observation_delivery_mode=no_change"))
    assertTrue(waitResult.content.contains("sandbox_command_observation_cursor_before=host_seq_1"))
    assertTrue(waitResult.content.contains("sandbox_command_observation_cursor_after=host_seq_2"))
    assertTrue(waitResult.content.contains("sandbox_command_observation_stdout_delta_bytes=0"))
    assertFalse(waitResult.content.contains("[stdout]"))
    assertTrue(waitResult.stdout.isBlank())
    assertEquals("no_change", waitResult.metadata["sandboxCommandObservationDeliveryMode"])
    assertEquals("host_seq_1", waitResult.metadata["sandboxCommandObservationCursorBefore"])
    assertEquals("host_seq_2", waitResult.metadata["sandboxCommandObservationCursorAfter"])
  }

  @Test
  fun firstProcessReadFallsBackToFullSnapshotWhenReconnectSeedCannotAlignWithUtf8Boundary() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-native-seed-baseline-utf8-reset").toPath()
    val seededStdout = "a\u20acready"
    val registry = SequencedObservationProcessRegistry(
      workspaceRoot = workspaceRoot,
      startedPlan = observationPlan(
        stdout = seededStdout,
        cursor = "host_seq_2",
        metadata = reconnectSeedMetadata(
          cursor = "host_seq_1",
          stdoutBytes = 2L,
        ),
      ),
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    val readResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(readResult.content.contains("sandbox_command_observation_delivery_mode=reset_full"))
    assertTrue(readResult.content.contains("sandbox_command_observation_cursor_before=host_seq_1"))
    assertTrue(readResult.content.contains("sandbox_command_observation_cursor_after=host_seq_2"))
    assertTrue(
      readResult.content.contains(
        "observation_warning=persisted reconnect seed could not be aligned with stdout bytes; returning full snapshot output",
      ),
    )
    assertTrue(readResult.content.contains("[stdout]"))
    assertTrue(readResult.content.contains(seededStdout))
    assertEquals(seededStdout, readResult.stdout.trim())
    assertEquals("reset_full", readResult.metadata["sandboxCommandObservationDeliveryMode"])
  }

  @Test
  fun processReadAndWaitReturnIncrementalOutputWhenHostObservationCursorAdvances() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-native-incremental-delivery").toPath()
    val registry = SequencedObservationProcessRegistry(
      workspaceRoot = workspaceRoot,
      startedPlan = observationPlan(
        stdout = "booting",
        cursor = "host_seq_1",
      ),
      readPlans = mutableListOf(
        observationPlan(
          stdout = "booting\nready",
          cursor = "host_seq_2",
        ),
      ),
      waitPlans = mutableListOf(
        observationPlan(
          status = ManagedProcessStatus.SUCCESS,
          stdout = "booting\nready\ndone",
          cursor = "host_seq_3",
          exitCode = 0,
          finishedAtEpochMs = 1_250L,
        ),
      ),
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    val firstRead = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )
    val secondRead = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )
    val waitResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessWait",
        arguments = JsonObject(
          mapOf(
            "process_id" to JsonPrimitive(processId),
            "timeout_ms" to JsonPrimitive(250),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(firstRead.content.contains("sandbox_command_observation_delivery_mode=full_snapshot"))
    assertTrue(firstRead.content.contains("sandbox_command_observation_cursor_before=none"))
    assertTrue(firstRead.content.contains("sandbox_command_observation_cursor_after=host_seq_1"))
    assertTrue(firstRead.content.contains("sandbox_command_observation_stdout_delta_bytes=7"))
    assertTrue(firstRead.content.contains("[stdout]"))
    assertTrue(firstRead.content.contains("booting"))
    assertEquals("booting", firstRead.stdout.trim())
    assertEquals("full_snapshot", firstRead.metadata["sandboxCommandObservationDeliveryMode"])

    assertTrue(secondRead.content.contains("sandbox_command_observation_delivery_mode=delta"))
    assertTrue(secondRead.content.contains("sandbox_command_observation_cursor_before=host_seq_1"))
    assertTrue(secondRead.content.contains("sandbox_command_observation_cursor_after=host_seq_2"))
    assertTrue(secondRead.content.contains("sandbox_command_observation_stdout_delta_bytes=6"))
    assertTrue(secondRead.content.contains("[stdout]"))
    assertTrue(secondRead.content.contains("ready"))
    assertFalse(secondRead.content.contains("[stdout]\nbooting"))
    assertEquals("ready", secondRead.stdout.trim())
    assertEquals("delta", secondRead.metadata["sandboxCommandObservationDeliveryMode"])
    assertEquals("host_seq_1", secondRead.metadata["sandboxCommandObservationCursorBefore"])
    assertEquals("host_seq_2", secondRead.metadata["sandboxCommandObservationCursorAfter"])

    assertTrue(waitResult.content.contains("sandbox_command_observation_delivery_mode=delta"))
    assertTrue(waitResult.content.contains("sandbox_command_observation_cursor_before=host_seq_2"))
    assertTrue(waitResult.content.contains("sandbox_command_observation_cursor_after=host_seq_3"))
    assertTrue(waitResult.content.contains("sandbox_command_observation_stdout_delta_bytes=5"))
    assertTrue(waitResult.content.contains("[stdout]"))
    assertTrue(waitResult.content.contains("done"))
    assertFalse(waitResult.content.contains("[stdout]\nbooting"))
    assertEquals("done", waitResult.stdout.trim())
    assertEquals("delta", waitResult.metadata["sandboxCommandObservationDeliveryMode"])
    assertEquals("host_seq_2", waitResult.metadata["sandboxCommandObservationCursorBefore"])
    assertEquals("host_seq_3", waitResult.metadata["sandboxCommandObservationCursorAfter"])
  }

  @Test
  fun processReadFallsBackToFullSnapshotWhenHostObservationCursorRegresses() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-native-incremental-reset").toPath()
    val registry = SequencedObservationProcessRegistry(
      workspaceRoot = workspaceRoot,
      startedPlan = observationPlan(
        stdout = "booting\nready",
        cursor = "host_seq_2",
      ),
      readPlans = mutableListOf(
        observationPlan(
          stdout = "fresh",
          cursor = "host_seq_1",
        ),
      ),
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )
    val regressedRead = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(regressedRead.content.contains("sandbox_command_observation_delivery_mode=reset_full"))
    assertTrue(regressedRead.content.contains("sandbox_command_observation_cursor_before=host_seq_2"))
    assertTrue(regressedRead.content.contains("sandbox_command_observation_cursor_after=host_seq_1"))
    assertTrue(
      regressedRead.content.contains(
        "observation_warning=host observation cursor regressed or output window changed; returning full snapshot output",
      ),
    )
    assertTrue(regressedRead.content.contains("[stdout]"))
    assertTrue(regressedRead.content.contains("fresh"))
    assertEquals("fresh", regressedRead.stdout.trim())
    assertEquals("reset_full", regressedRead.metadata["sandboxCommandObservationDeliveryMode"])
  }

  @Test
  fun safeModeProcessTerminateRequiresHighRiskApprovalBeforeTermination() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-safe-terminate").toPath()
    val registry = RecordingProcessRegistry(workspaceRoot = workspaceRoot)
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    val terminateResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "SAFE")),
      call = AgentToolCall(
        toolName = "ProcessTerminate",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, terminateResult.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", terminateResult.errorCode)
    assertEquals("HIGH_RISK", terminateResult.metadata["approvalRisk"])
    assertEquals("process_control", terminateResult.metadata["capabilityKind"])
    assertEquals("process", terminateResult.metadata["targetKind"])
    assertEquals("inside_workspace", terminateResult.metadata["workspaceRelation"])
    assertEquals(processId, terminateResult.metadata["targetSummary"])
    assertEquals("process_lifecycle", terminateResult.metadata["intentCategory"])
    assertEquals("terminate", terminateResult.metadata["processLifecycleIntentKind"])
    assertEquals(processId, terminateResult.metadata["intentProcessId"])
    assertEquals(".", terminateResult.metadata["intentWorkingDirectory"])
    assertEquals(0, registry.terminateCount)
    assertEquals("RUNNING", requireNotNull(registry.read(processId)).status.name)
  }

  @Test
  fun developerModeProcessStartCanLaunchManagedPythonScript() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-python").toPath()
    Files.createDirectories(workspaceRoot.resolve("scripts"))
    Files.write(
      workspaceRoot.resolve("scripts").resolve("run.py"),
      "print('hello')".toByteArray(StandardCharsets.UTF_8),
    )
    val registry = RecordingProcessRegistry(workspaceRoot = workspaceRoot)
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "script_path" to JsonPrimitive("scripts/run.py"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("--flag"))
            },
            "python_executable" to JsonPrimitive("python3"),
            "timeout_ms" to JsonPrimitive(5000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    val startRequest = registry.startRequests.single()
    assertEquals(AgentToolResultStatus.SUCCESS, startResult.status)
    assertEquals("python3", startRequest.command)
    assertEquals(listOf("-m", "python_runner.runner", "exec"), startRequest.args.take(3))
    val workspaceArgIndex = startRequest.args.indexOf("--workspace")
    assertTrue(workspaceArgIndex >= 0)
    assertEquals(startRequest.workingDirectory, startRequest.args[workspaceArgIndex + 1])
    assertTrue(startRequest.args.contains("--script"))
    assertTrue(startRequest.args.any { argument -> argument.endsWith("scripts${java.io.File.separator}run.py") })
    assertTrue(startRequest.args.contains("--timeout-seconds"))
    assertTrue(startRequest.args.contains("5.0"))
    assertEquals(listOf("--", "--flag"), startRequest.args.takeLast(2))
    assertTrue(startRequest.workingDirectory.orEmpty().endsWith(workspaceRoot.fileName.toString()))
    assertEquals("python_exec", startRequest.metadata["runtimeKind"])
    assertEquals("scripts/run.py", startRequest.metadata["scriptPath"])
    assertEquals("python3", startRequest.metadata["pythonExecutable"])
    assertEquals("execute_command", startResult.metadata["capabilityKind"])
    assertEquals("script", startResult.metadata["targetKind"])
    assertEquals("inside_workspace", startResult.metadata["workspaceRelation"])
    assertEquals("scripts/run.py", startResult.metadata["primaryTargetPath"])
    assertEquals(".", startResult.metadata["secondaryTargetPath"])
    assertEquals("scripts/run.py", startResult.metadata["targetSummary"])
    assertEquals("scripts/run.py", startResult.metadata["scriptPath"])
    assertEquals("execution", startResult.metadata["intentCategory"])
    assertEquals("managed_python_script", startResult.metadata["executionIntentKind"])
    assertEquals("managed_process", startResult.metadata["executionTransport"])
    assertEquals("scripts/run.py", startResult.metadata["executionScriptPath"])
    assertEquals(".", startResult.metadata["executionWorkingDirectory"])
    assertTrue(startResult.content.contains("runtime_kind=python_exec"))
    assertTrue(startResult.content.contains("script_path=scripts/run.py"))
    assertTrue(startResult.content.contains("python_executable=python3"))
  }

  @Test
  fun developerModeProcessStartCanLaunchManagedPythonScriptThroughRuntimeAdapter() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-python-runtime").toPath()
    Files.createDirectories(workspaceRoot.resolve("scripts"))
    Files.write(
      workspaceRoot.resolve("scripts").resolve("run.py"),
      "print('hello from runtime adapter')".toByteArray(StandardCharsets.UTF_8),
    )
    val pythonRuntime = BlockingPythonScriptRuntime()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = InMemoryAgentProcessRegistry(
          controllerFactory = RoutedManagedProcessControllerFactory(
            workspaceRoot = workspaceRoot,
            pythonRuntime = pythonRuntime,
          ),
        ),
        managedPythonProcessUsesRuntimeAdapter = true,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "script_path" to JsonPrimitive("scripts/run.py"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("--flag"))
            },
            "python_executable" to JsonPrimitive("python3"),
            "timeout_ms" to JsonPrimitive(5000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    assertTrue(pythonRuntime.started.await(1, TimeUnit.SECONDS))

    val processId = requireNotNull(startResult.metadata["processId"])
    val request = requireNotNull(pythonRuntime.lastRequest)
    assertEquals(AgentToolResultStatus.SUCCESS, startResult.status)
    assertEquals(workspaceRoot.resolve("scripts").resolve("run.py").toRealPath(), request.scriptPath)
    assertEquals(listOf("--flag"), request.args)
    assertEquals(5000L, request.timeoutMs)
    assertEquals(processId, request.requestId)
    assertEquals("python_exec", startResult.metadata["runtimeKind"])
    assertEquals("unsupported", startResult.metadata["terminationSupport"])
    assertEquals("execution", startResult.metadata["intentCategory"])
    assertEquals("managed_python_script", startResult.metadata["executionIntentKind"])
    assertEquals("managed_process", startResult.metadata["executionTransport"])
    assertEquals("scripts/run.py", startResult.metadata["executionScriptPath"])
    assertEquals(".", startResult.metadata["executionWorkingDirectory"])
    assertTrue(startResult.content.contains("status=running"))
    assertTrue(startResult.content.contains("command=python_exec"))

    val readWhileRunning = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )
    assertTrue(readWhileRunning.content.contains("status=running"))

    val terminateWhileRunning = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessTerminate",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )
    assertTrue(terminateWhileRunning.content.contains("does not support termination"))
    assertEquals("true", terminateWhileRunning.metadata["terminationRequested"])
    assertEquals("process_lifecycle", terminateWhileRunning.metadata["intentCategory"])
    assertEquals("terminate", terminateWhileRunning.metadata["processLifecycleIntentKind"])
    assertEquals(processId, terminateWhileRunning.metadata["intentProcessId"])
    assertEquals(".", terminateWhileRunning.metadata["intentWorkingDirectory"])

    pythonRuntime.finish.countDown()

    val waitResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessWait",
        arguments = JsonObject(
          mapOf(
            "process_id" to JsonPrimitive(processId),
            "timeout_ms" to JsonPrimitive(250),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(waitResult.content.contains("status=success"))
    assertTrue(waitResult.content.contains("runtime_kind=python_exec"))
    assertTrue(waitResult.content.contains("termination_requested=true"))
    assertEquals("p4a-test", waitResult.metadata["runtimeBackend"])
  }

  @Test
  fun developerModeProcessTerminateCancelsManagedPythonScriptThroughRuntimeAdapter() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-python-runtime-cancel").toPath()
    Files.createDirectories(workspaceRoot.resolve("scripts"))
    Files.write(
      workspaceRoot.resolve("scripts").resolve("run.py"),
      "print('hello from cancellable runtime adapter')".toByteArray(StandardCharsets.UTF_8),
    )
    val pythonRuntime = CancellableBlockingPythonScriptRuntime()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = InMemoryAgentProcessRegistry(
          controllerFactory = RoutedManagedProcessControllerFactory(
            workspaceRoot = workspaceRoot,
            pythonRuntime = pythonRuntime,
          ),
        ),
        managedPythonProcessUsesRuntimeAdapter = true,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "script_path" to JsonPrimitive("scripts/run.py"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("--flag"))
            },
            "timeout_ms" to JsonPrimitive(5000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    assertTrue(pythonRuntime.started.await(1, TimeUnit.SECONDS))

    val processId = requireNotNull(startResult.metadata["processId"])
    val request = requireNotNull(pythonRuntime.lastRequest)
    assertEquals(AgentToolResultStatus.SUCCESS, startResult.status)
    assertEquals(processId, request.requestId)
    assertEquals("cooperative", startResult.metadata["terminationSupport"])
    assertEquals("execution", startResult.metadata["intentCategory"])
    assertEquals("managed_python_script", startResult.metadata["executionIntentKind"])
    assertEquals("managed_process", startResult.metadata["executionTransport"])
    assertEquals("scripts/run.py", startResult.metadata["executionScriptPath"])
    assertEquals(".", startResult.metadata["executionWorkingDirectory"])

    val terminateWhileRunning = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessTerminate",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(terminateWhileRunning.content.contains("cancellation requested"))
    assertEquals("true", terminateWhileRunning.metadata["terminationRequested"])
    assertEquals("true", terminateWhileRunning.metadata["terminationRequestAccepted"])
    assertEquals("process_lifecycle", terminateWhileRunning.metadata["intentCategory"])
    assertEquals("terminate", terminateWhileRunning.metadata["processLifecycleIntentKind"])
    assertEquals(processId, terminateWhileRunning.metadata["intentProcessId"])
    assertEquals(".", terminateWhileRunning.metadata["intentWorkingDirectory"])

    val waitResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessWait",
        arguments = JsonObject(
          mapOf(
            "process_id" to JsonPrimitive(processId),
            "timeout_ms" to JsonPrimitive(500),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertTrue(waitResult.content.contains("status=cancelled"))
    assertTrue(waitResult.content.contains("termination_support=cooperative"))
    assertTrue(waitResult.content.contains("termination_request_accepted=true"))
    assertEquals(130, waitResult.exitCode)
    assertEquals("CANCELLED", waitResult.errorCode)
    assertEquals("true", waitResult.metadata["cancelled"])
    assertEquals("p4a-cancellable-test", waitResult.metadata["runtimeBackend"])
  }

  @Test
  fun managedPythonProcessStartCanBeDisabledPerRuntime() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-python-disabled").toPath()
    Files.createDirectories(workspaceRoot.resolve("scripts"))
    Files.write(
      workspaceRoot.resolve("scripts").resolve("run.py"),
      "print('hello')".toByteArray(StandardCharsets.UTF_8),
    )
    val registry = RecordingProcessRegistry(workspaceRoot = workspaceRoot)
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
        supportsManagedPythonProcessStart = false,
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "script_path" to JsonPrimitive("scripts/run.py"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("--flag"))
            },
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("PROCESSSTART_PYTHON_UNSUPPORTED", result.errorCode)
    assertEquals("scripts/run.py", result.metadata["scriptPath"])
    assertEquals("managed_python_process_start_disabled", result.metadata["runtimeCapability"])
    assertTrue(result.content.contains("Use python_exec"))
    assertEquals(0, registry.startCount)
  }

  @Test
  fun processReadFailsCleanlyWhenProcessIsMissing() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-missing").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = RecordingProcessRegistry(workspaceRoot = workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive("proc-missing"))),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("PROCESS_NOT_FOUND", result.errorCode)
    assertTrue(result.content.contains("proc-missing"))
  }

  private fun agentTask(
    id: String = "task-${System.nanoTime()}",
    policyDecision: PolicyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = id,
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = policyDecision,
    metadata = metadata,
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in AgentManagedProcessToolTest.") },
  )

  private fun observationPlan(
    status: ManagedProcessStatus = ManagedProcessStatus.RUNNING,
    stdout: String = "",
    stderr: String = "",
    cursor: String,
    providerCursor: String? = null,
    providerEventCount: Long? = null,
    providerMode: String = "provider_event_stream_host_buffered",
    exitCode: Int? = null,
    finishedAtEpochMs: Long? = null,
    metadata: Map<String, String> = emptyMap(),
  ): ObservationSnapshotPlan = ObservationSnapshotPlan(
    status = status,
    stdout = stdout,
    stderr = stderr,
    exitCode = exitCode,
    finishedAtEpochMs = finishedAtEpochMs,
    metadata = metadata + hostObservationMetadata(
      cursor = cursor,
      stdout = stdout,
      stderr = stderr,
      providerCursor = providerCursor,
      providerEventCount = providerEventCount,
      providerMode = providerMode,
    ),
  )

  private fun hostObservationMetadata(
    cursor: String,
    stdout: String,
    stderr: String,
    providerCursor: String? = null,
    providerEventCount: Long? = null,
    providerMode: String = "provider_event_stream_host_buffered",
  ): Map<String, String> = buildMap {
    put("runtimeBackend", "e2b_envd_native_command")
    put("runtimeTransport", "connect_proto_minimal")
    put("sandboxCommandBackendKind", "provider_native")
    put("sandboxCommandBackendResolvedKind", "provider_native")
    put("sandboxCommandProviderNative", "true")
    put("sandboxCommandSupportsReconnect", "true")
    put("sandboxCommandObservationMode", "host_managed_snapshot")
    put("sandboxCommandObservationCursor", cursor)
    put("sandboxCommandObservationStdoutBytes", stdout.toByteArray(StandardCharsets.UTF_8).size.toString())
    put("sandboxCommandObservationStderrBytes", stderr.toByteArray(StandardCharsets.UTF_8).size.toString())
    providerCursor?.takeIf(String::isNotBlank)?.let { currentProviderCursor ->
      put("sandboxCommandProviderObservationMode", providerMode)
      put("sandboxCommandProviderObservationCursor", currentProviderCursor)
      put(
        "sandboxCommandProviderObservationEventCount",
        (providerEventCount ?: 0L).toString(),
      )
    }
  }

  private fun reconnectSeedMetadata(
    cursor: String,
    stdoutBytes: Long,
    stderrBytes: Long = 0L,
    providerCursor: String? = null,
    providerEventCount: Long? = null,
    providerSeedSource: String = "durable_snapshot_metadata",
  ): Map<String, String> = buildMap {
    put("sandboxCommandReconnectSeedSource", "durable_snapshot_metadata")
    put("sandboxCommandReconnectSeedObservationCursor", cursor)
    put("sandboxCommandReconnectSeededStdoutBytes", stdoutBytes.toString())
    put("sandboxCommandReconnectSeededStderrBytes", stderrBytes.toString())
    providerCursor?.takeIf(String::isNotBlank)?.let { reconnectProviderCursor ->
      put("sandboxCommandReconnectSeedProviderObservationCursor", reconnectProviderCursor)
      put(
        "sandboxCommandReconnectSeedProviderObservationEventCount",
        (providerEventCount ?: 0L).toString(),
      )
      put("sandboxCommandReconnectProviderObservationSeedSource", providerSeedSource)
    }
  }

  private class RecordingProcessRegistry(
    private val workspaceRoot: Path,
    private val startedSnapshotStatus: ManagedProcessStatus = ManagedProcessStatus.RUNNING,
    private val startedSnapshotErrorCode: String? = null,
    private val startedSnapshotErrorMessage: String? = null,
    private val startedSnapshotMetadata: Map<String, String> = emptyMap(),
    private val waitedSnapshotMetadata: Map<String, String> = emptyMap(),
  ) : AgentProcessRegistry {
    val startRequests = mutableListOf<ManagedProcessStartRequest>()
    val waitSnapshots = mutableListOf<ManagedProcessSnapshot>()
    var startCount: Int = 0
      private set
    var terminateCount: Int = 0
      private set
    private val snapshotsById = linkedMapOf<String, ManagedProcessSnapshot>()

    override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
      startCount += 1
      startRequests += request
      val snapshot = ManagedProcessSnapshot(
        processId = request.processId,
        taskId = request.taskId,
        command = request.command,
        args = request.args,
        workingDirectory = request.workingDirectory,
        status = startedSnapshotStatus,
        processStarted = true,
        timeoutMs = request.timeoutMs,
        errorCode = startedSnapshotErrorCode,
        errorMessage = startedSnapshotErrorMessage,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        finishedAtEpochMs = if (startedSnapshotStatus.isTerminal) 1_000L else null,
        metadata = request.metadata + startedSnapshotMetadata,
      )
      snapshotsById[request.processId] = snapshot
      return snapshot
    }

    override fun list(): List<ManagedProcessSnapshot> = snapshotsById.values.toList()

    override fun read(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]

    override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? {
      val existing = snapshotsById[processId] ?: return null
      val waited = existing.copy(
        status = ManagedProcessStatus.SUCCESS,
        stdout = "server ready",
        exitCode = 0,
        updatedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
        finishedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
        metadata = existing.metadata + waitedSnapshotMetadata,
      )
      snapshotsById[processId] = waited
      waitSnapshots += waited
      return waited
    }

    override fun terminate(processId: String): ManagedProcessSnapshot? {
      terminateCount += 1
      val existing = snapshotsById[processId] ?: return null
      val terminated = existing.copy(
        status = ManagedProcessStatus.CANCELLED,
        exitCode = 137,
        errorCode = "CANCELLED",
        errorMessage = "Managed process terminated.",
        updatedAtEpochMs = existing.updatedAtEpochMs + 1,
        finishedAtEpochMs = existing.updatedAtEpochMs + 1,
        cancelled = true,
      )
      snapshotsById[processId] = terminated
      return terminated
    }
  }

  private data class ObservationSnapshotPlan(
    val status: ManagedProcessStatus,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val finishedAtEpochMs: Long?,
    val metadata: Map<String, String>,
  ) {
    fun toSnapshot(
      request: ManagedProcessStartRequest,
      updatedAtEpochMs: Long,
    ): ManagedProcessSnapshot = ManagedProcessSnapshot(
      processId = request.processId,
      taskId = request.taskId,
      command = request.command,
      args = request.args,
      workingDirectory = request.workingDirectory,
      status = status,
      processStarted = true,
      timeoutMs = request.timeoutMs,
      stdout = stdout,
      stderr = stderr,
      exitCode = exitCode,
      startedAtEpochMs = 1_000L,
      updatedAtEpochMs = updatedAtEpochMs,
      finishedAtEpochMs = finishedAtEpochMs,
      metadata = request.metadata + metadata,
    )

    fun advance(
      existing: ManagedProcessSnapshot,
      updatedAtEpochMs: Long,
    ): ManagedProcessSnapshot = existing.copy(
      status = status,
      stdout = stdout,
      stderr = stderr,
      exitCode = exitCode,
      updatedAtEpochMs = updatedAtEpochMs,
      finishedAtEpochMs = finishedAtEpochMs,
      metadata = existing.metadata + metadata,
    )
  }

  private class SequencedObservationProcessRegistry(
    private val workspaceRoot: Path,
    private val startedPlan: ObservationSnapshotPlan,
    private val readPlans: MutableList<ObservationSnapshotPlan> = mutableListOf(),
    private val waitPlans: MutableList<ObservationSnapshotPlan> = mutableListOf(),
  ) : AgentProcessRegistry {
    private val snapshotsById = linkedMapOf<String, ManagedProcessSnapshot>()
    private var updatedAtEpochMs: Long = 1_000L

    override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
      val snapshot = startedPlan.toSnapshot(
        request = request,
        updatedAtEpochMs = updatedAtEpochMs,
      )
      snapshotsById[request.processId] = snapshot
      return snapshot
    }

    override fun list(): List<ManagedProcessSnapshot> = snapshotsById.values.toList()

    override fun read(processId: String): ManagedProcessSnapshot? {
      val current = snapshotsById[processId] ?: return null
      readPlans.removeFirstOrNull()?.let { nextPlan ->
        updatedAtEpochMs += 100L
        snapshotsById[processId] = nextPlan.advance(
          existing = current,
          updatedAtEpochMs = updatedAtEpochMs,
        )
      }
      return current
    }

    override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? {
      val current = snapshotsById[processId] ?: return null
      val nextPlan = waitPlans.removeFirstOrNull() ?: return current
      updatedAtEpochMs += timeoutMs.coerceAtLeast(1L)
      val waited = nextPlan.advance(
        existing = current,
        updatedAtEpochMs = updatedAtEpochMs,
      )
      snapshotsById[processId] = waited
      return waited
    }

    override fun terminate(processId: String): ManagedProcessSnapshot? {
      val existing = snapshotsById[processId] ?: return null
      val terminated = existing.copy(
        status = ManagedProcessStatus.CANCELLED,
        exitCode = 137,
        errorCode = "CANCELLED",
        errorMessage = "Managed process terminated.",
        updatedAtEpochMs = existing.updatedAtEpochMs + 1L,
        finishedAtEpochMs = existing.updatedAtEpochMs + 1L,
        cancelled = true,
      )
      snapshotsById[processId] = terminated
      return terminated
    }
  }

  private class BlockingPythonScriptRuntime : PythonScriptRuntime {
    val started: CountDownLatch = CountDownLatch(1)
    val finish: CountDownLatch = CountDownLatch(1)
    var lastRequest: PythonExecRequest? = null

    override fun exec(request: PythonExecRequest): ExecutionResult {
      lastRequest = request
      started.countDown()
      finish.await(1, TimeUnit.SECONDS)
      return ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.SUCCESS,
        exitCode = 0,
        stdout = "runtime adapter ok",
        stderr = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_050L,
        metadata = mapOf("runtimeBackend" to "p4a-test"),
      )
    }
  }

  private class CancellableBlockingPythonScriptRuntime : PythonScriptRuntime, CancellablePythonScriptRuntime {
    val started: CountDownLatch = CountDownLatch(1)
    var lastRequest: PythonExecRequest? = null
    private val cancellationRequested = AtomicBoolean(false)
    @Volatile private var cancelledRequestId: String? = null

    override fun exec(request: PythonExecRequest): ExecutionResult {
      lastRequest = request
      started.countDown()
      repeat(200) {
        if (cancellationRequested.get() && request.requestId == cancelledRequestId) {
          return ExecutionResult(
            taskId = request.taskId,
            status = ExecutionStatus.CANCELLED,
            exitCode = 130,
            stdout = "",
            stderr = "",
            errorCode = "CANCELLED",
            errorMessage = "Python script cancelled.",
            startedAtEpochMs = 2_000L,
            finishedAtEpochMs = 2_050L,
            metadata = mapOf("runtimeBackend" to "p4a-cancellable-test"),
          )
        }
        Thread.sleep(10)
      }
      return ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.SUCCESS,
        exitCode = 0,
        stdout = "runtime adapter ok",
        stderr = "",
        startedAtEpochMs = 2_000L,
        finishedAtEpochMs = 4_000L,
        metadata = mapOf("runtimeBackend" to "p4a-cancellable-test"),
      )
    }

    override fun requestCancellation(requestId: String): Boolean {
      cancelledRequestId = requestId
      cancellationRequested.set(true)
      return true
    }
  }
}
