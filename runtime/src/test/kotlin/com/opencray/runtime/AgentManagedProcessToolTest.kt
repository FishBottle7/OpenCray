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
        "sandboxCommandObservationMode" to "host_managed_snapshot",
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
        "sandboxCommandReconnectLastAttachedAtEpochMs" to "1500",
        "sandboxCommandReconnectLastEventAtEpochMs" to "1600",
        "sandboxCommandReconnectLastEventKind" to "data",
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
    assertTrue(readResult.content.contains("sandbox_observation_mode=host_managed_snapshot"))
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
    assertTrue(readResult.content.contains("sandbox_command_reconnect_last_attached_at_epoch_ms=1500"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_last_event_at_epoch_ms=1600"))
    assertTrue(readResult.content.contains("sandbox_command_reconnect_last_event_kind=data"))
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
    assertEquals("true", readResult.metadata["sandboxCommandSupportsReconnect"])
    assertEquals(
      "seed_snapshot_then_live_attach",
      readResult.metadata["sandboxCommandReconnectResumeMode"],
    )
    assertEquals("attached_live", readResult.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("true", readResult.metadata["sandboxCommandReconnectOutputGapRisk"])
    assertEquals("1500", readResult.metadata["sandboxCommandReconnectLastAttachedAtEpochMs"])
    assertEquals("1600", readResult.metadata["sandboxCommandReconnectLastEventAtEpochMs"])
    assertEquals("data", readResult.metadata["sandboxCommandReconnectLastEventKind"])

    assertTrue(waitResult.content.contains("runtime_backend=e2b_envd_native_command"))
    assertTrue(waitResult.content.contains("sandbox_command_api=envd_process_start"))
    assertTrue(waitResult.content.contains("sandbox_command_native_protocol=envd_connect_process_v1"))
    assertTrue(waitResult.content.contains("sandbox_command_session_source=persisted"))
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
        "sandboxCommandReconnectRetryable" to "true",
        "sandboxCommandReconnectRetryAfterEpochMs" to "2200",
        "sandboxCommandReconnectAttemptCount" to "1",
        "sandboxCommandReconnectFailureStage" to "transport_exception_after_connect",
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
    assertTrue(
      readResult.content.contains(
        "observation_warning=provider reconnect failed without terminal process state; a later ProcessRead or ProcessWait may retry attach after backoff",
      ),
    )
    assertEquals("true", readResult.metadata["sandboxCommandReconnectRetryable"])
    assertEquals("retry_scheduled", readResult.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("1200", readResult.metadata["sandboxCommandReconnectLastFailureAtEpochMs"])
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

  private class RecordingProcessRegistry(
    private val workspaceRoot: Path,
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
        status = ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = request.timeoutMs,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
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
