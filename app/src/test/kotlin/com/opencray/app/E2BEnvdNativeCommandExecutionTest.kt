package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.CommandExecutionRequest
import com.opencray.runtime.PythonExecRequest
import com.opencray.runtime.PythonScriptRuntime
import com.opencray.runtime.process.ManagedProcessDeliveredObservationState
import com.opencray.runtime.process.ManagedProcessObservationState
import com.opencray.runtime.process.ManagedProcessReconnectSeed
import com.opencray.runtime.process.ManagedProcessReconnectState
import com.opencray.runtime.process.ManagedProcessRemoteHandle
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.process.ReconnectableManagedProcessControllerFactory
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class E2BEnvdNativeCommandExecutionTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  @Test
  fun nativeForegroundCommandUsesEnvdConnectWhenReusableSessionIsAvailable() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-command-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("repo"))
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-native",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-native",
        ),
      )
    }
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { _, onEnvelope ->
        onEnvelope(
          0,
          E2BEnvdProcessProtoCodec.encodeStartResponse(
            E2BEnvdProcessEvent.Start(pid = 321),
          ),
        )
        onEnvelope(
          0,
          E2BEnvdProcessProtoCodec.encodeStartResponse(
            E2BEnvdProcessEvent.Data(stdout = "native stdout".toByteArray(StandardCharsets.UTF_8)),
          ),
        )
        onEnvelope(
          0,
          E2BEnvdProcessProtoCodec.encodeStartResponse(
            E2BEnvdProcessEvent.Data(stderr = "native stderr".toByteArray(StandardCharsets.UTF_8)),
          ),
        )
        onEnvelope(
          0,
          E2BEnvdProcessProtoCodec.encodeStartResponse(
            E2BEnvdProcessEvent.End(
              exitCode = 0,
              exited = true,
              status = "done",
            ),
          ),
        )
        onEnvelope(0x02, "{}".toByteArray(StandardCharsets.UTF_8))
        E2BResponse(statusCode = 200)
      }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = RecordingPythonRuntime(),
      transport = transport,
      json = json,
    )

    val result = backend.createCommandExecutor().execute(
      request = CommandExecutionRequest(
        taskId = "task-native-command",
        command = "git",
        args = listOf("status"),
        workingDirectory = workspaceRoot.resolve("repo").toString(),
        requestedAtEpochMs = 100L,
      ),
      policyDecision = allowPolicy(),
      approvalToken = null,
      hooks = hooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("native stdout", result.stdout)
    assertEquals("native stderr", result.stderr)
    assertEquals("e2b_envd_native_command", result.metadata["runtimeBackend"])
    assertEquals("connect_proto_minimal", result.metadata["runtimeTransport"])
    assertEquals("envd_process_start", result.metadata["sandboxCommandApi"])
    assertEquals("envd_connect_process_v1", result.metadata["sandboxCommandNativeProtocol"])
    assertEquals("provider_native", result.metadata["sandboxCommandBackendKind"])
    assertEquals("provider_native_preferred", result.metadata["sandboxCommandBackendRequestedKind"])
    assertEquals("provider_native", result.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals("true", result.metadata["sandboxCommandProviderNativeAvailable"])
    assertEquals("false", result.metadata["sandboxCommandSupportsStreamingLogs"])
    assertEquals("true", result.metadata["sandboxCommandSupportsReconnect"])
    assertEquals("true", result.metadata["sandboxCommandSupportsManagedProcessLiveObservation"])
    assertEquals(
      "false",
      result.metadata["sandboxCommandSupportsManagedProcessObservationCursorResume"],
    )
    assertEquals("false", result.metadata["sandboxCommandSupportsManagedProcessObservationBackfill"])
    assertEquals(
      "host_buffered_seed_then_live_attach",
      result.metadata["sandboxCommandProviderObservationResumeContract"],
    )
    assertEquals(
      "envd_connect_request_selector_only",
      result.metadata["sandboxCommandProviderObservationResumeBlocker"],
    )
    assertEquals("provider_native", result.metadata["sandboxTraceBackendKind"])
    assertTrue(!result.metadata["sandboxTraceId"].isNullOrBlank())
    assertTrue(!result.metadata["sandboxTraceBackendSpanId"].isNullOrBlank())
    assertTrue(!result.metadata["sandboxTraceProviderStartSpanId"].isNullOrBlank())
    assertEquals(
      result.metadata["sandboxTraceBackendSpanId"],
      result.metadata["sandboxTraceProviderStartParentSpanId"],
    )
    assertEquals("persisted", result.metadata["sandboxCommandSessionSource"])
    assertEquals("envd_process", result.metadata["sandboxCommandProviderHandleKind"])
    assertEquals("tag", result.metadata["sandboxCommandProviderStableSelectorKind"])
    val stableSelectorValue = result.metadata["sandboxCommandProviderStableSelectorValue"]
    assertNotNull(stableSelectorValue)
    assertTrue(stableSelectorValue!!.startsWith("cmd-"))
    assertEquals("tag", result.metadata["sandboxCommandIdKind"])
    assertEquals(stableSelectorValue, result.metadata["sandboxCommandId"])
    assertEquals("pid", result.metadata["sandboxCommandProviderLiveSelectorKind"])
    assertEquals("321", result.metadata["sandboxCommandProviderLiveSelectorValue"])
    assertEquals("321", result.metadata["sandboxCommandPid"])
    assertEquals("200", result.metadata["sandboxCommandNativeHttpStatusCode"])
    assertEquals("done", result.metadata["sandboxCommandNativeProcessStatus"])
    assertEquals("true", result.metadata["sandboxCommandNativeProcessExited"])

    val request = transport.requests.single()
    assertEquals("envd-token", request.headers["X-Access-Token"])
    assertTrue(request.url.contains("https://49983-sandbox-native.e2b.app/process.Process/Start"))
    val startRequest = E2BEnvdProcessProtoCodec.decodeStartRequest(connectPayload(request.bodyBytes))
    assertEquals("git", startRequest.process.cmd)
    assertEquals(listOf("status"), startRequest.process.args)
    assertEquals("/home/user/opencray/workspace-sticky/sandbox-native/repo", startRequest.process.cwd)
    assertEquals(stableSelectorValue, startRequest.tag)
  }

  @Test
  fun nativeForegroundCommandFallsBackToPythonWrapperWhenRemoteWorkspaceRootIsMissing() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-command-fallback").toPath()
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-fallback",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = null,
        ),
      )
    }
    val pythonRuntime = RecordingPythonRuntime(
      result = ExecutionResult(
        taskId = "task-wrapper",
        status = ExecutionStatus.SUCCESS,
        exitCode = 0,
        stdout = encodedWrapperPayload(
          CommandWrapperResultPayload(
            exitCode = 0,
            stdout = "wrapper stdout",
            stderr = "",
            processStarted = true,
          ),
        ),
        stderr = "",
        startedAtEpochMs = 100L,
        finishedAtEpochMs = 200L,
        metadata = mapOf("runtimeBackend" to "e2b_code_interpreter"),
      ),
    )
    val transport = FakeEnvdCommandTransport()
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = pythonRuntime,
      transport = transport,
      json = json,
    )

    val result = backend.createCommandExecutor().execute(
      request = CommandExecutionRequest(
        taskId = "task-wrapper",
        command = "git",
        args = listOf("status"),
        workingDirectory = workspaceRoot.toString(),
        requestedAtEpochMs = 100L,
      ),
      policyDecision = allowPolicy(),
      approvalToken = null,
      hooks = hooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("wrapper stdout", result.stdout)
    assertEquals("python_wrapper", result.metadata["sandboxCommandBackendKind"])
    assertEquals("python_wrapper", result.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals(
      "native_command_remote_workspace_unavailable",
      result.metadata["sandboxCommandBackendFallbackReasonCode"],
    )
    assertTrue(transport.requests.isEmpty())
    assertTrue(pythonRuntime.requests.isNotEmpty())
  }

  @Test
  fun nativeForegroundCommandFallsBackToPythonWrapperWithNativeAttemptMetadataOnTransportFailure() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-command-transport-failure").toPath()
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-transport-failure",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-transport-failure",
        ),
      )
    }
    val pythonRuntime = RecordingPythonRuntime(
      result = ExecutionResult(
        taskId = "task-transport-wrapper",
        status = ExecutionStatus.SUCCESS,
        exitCode = 0,
        stdout = encodedWrapperPayload(
          CommandWrapperResultPayload(
            exitCode = 0,
            stdout = "wrapper stdout after transport failure",
            stderr = "",
            processStarted = true,
          ),
        ),
        stderr = "",
        startedAtEpochMs = 100L,
        finishedAtEpochMs = 200L,
        metadata = mapOf("runtimeBackend" to "e2b_code_interpreter"),
      ),
    )
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { _, _ -> throw EOFException("socket closed") }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = pythonRuntime,
      transport = transport,
      json = json,
    )

    val result = backend.createCommandExecutor().execute(
      request = CommandExecutionRequest(
        taskId = "task-transport-wrapper",
        command = "git",
        args = listOf("status"),
        workingDirectory = workspaceRoot.toString(),
        requestedAtEpochMs = 100L,
      ),
      policyDecision = allowPolicy(),
      approvalToken = null,
      hooks = hooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("wrapper stdout after transport failure", result.stdout)
    assertEquals("python_wrapper", result.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals(
      "native_command_transport_error",
      result.metadata["sandboxCommandBackendFallbackReasonCode"],
    )
    assertEquals("true", result.metadata["sandboxCommandNativeAttempted"])
    assertEquals("envd_process_start", result.metadata["sandboxCommandNativeAttemptApi"])
    assertEquals("connect_proto_minimal", result.metadata["sandboxCommandNativeAttemptTransport"])
    assertEquals("envd_connect_process_v1", result.metadata["sandboxCommandNativeAttemptProtocol"])
    assertEquals("persisted", result.metadata["sandboxCommandNativeAttemptSessionSource"])
    assertEquals(
      "/home/user/opencray/workspace-sticky/sandbox-transport-failure",
      result.metadata["sandboxCommandNativeAttemptRemoteWorkingDirectory"],
    )
    assertEquals(
      "transport_exception",
      result.metadata["sandboxCommandNativeAttemptFailureStage"],
    )
    assertEquals(
      "EOFException",
      result.metadata["sandboxCommandNativeAttemptTransportFailureClass"],
    )
    assertEquals(
      "socket closed",
      result.metadata["sandboxCommandNativeAttemptTransportFailureMessage"],
    )
    assertEquals(1, transport.requests.size)
    assertTrue(pythonRuntime.requests.isNotEmpty())
  }

  @Test
  fun nativeManagedProcessUsesEnvdStartAndSendSignalWhenReusableSessionIsAvailable() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-managed-command").toPath()
    Files.createDirectories(workspaceRoot.resolve("repo"))
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-managed-native",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-managed-native",
        ),
      )
    }
    val streamStarted = CountDownLatch(1)
    val allowStreamComplete = CountDownLatch(1)
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { _, onEnvelope ->
        streamStarted.countDown()
        onEnvelope(
          0,
          E2BEnvdProcessProtoCodec.encodeStartResponse(
            E2BEnvdProcessEvent.Start(pid = 654),
          ),
        )
        onEnvelope(
          0,
          E2BEnvdProcessProtoCodec.encodeStartResponse(
            E2BEnvdProcessEvent.Data(stdout = "booting".toByteArray(StandardCharsets.UTF_8)),
          ),
        )
        assertTrue(allowStreamComplete.await(5, TimeUnit.SECONDS))
        onEnvelope(
          0,
          E2BEnvdProcessProtoCodec.encodeStartResponse(
            E2BEnvdProcessEvent.End(
              exitCode = 137,
              exited = true,
              status = "killed",
              error = "terminated",
            ),
          ),
        )
        onEnvelope(0x02, "{}".toByteArray(StandardCharsets.UTF_8))
        E2BResponse(statusCode = 200)
      }
      unaryHandler = { _ ->
        allowStreamComplete.countDown()
        E2BResponse(statusCode = 200)
      }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = RecordingPythonRuntime(),
      transport = transport,
      json = json,
    )

    val controller = backend.createManagedProcessControllerFactory().start(
      com.opencray.runtime.process.ManagedProcessStartRequest(
        processId = "proc-native-managed",
        taskId = "task-native-managed",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = workspaceRoot.resolve("repo").toString(),
        timeoutMs = 5_000L,
        requestedAtEpochMs = 100L,
        metadata = mapOf("executionBackend" to "sandbox_remote"),
      ),
    )

    assertTrue(streamStarted.await(5, TimeUnit.SECONDS))
    waitUntil {
      controller.snapshot().metadata["sandboxCommandPid"] == "654"
    }
    val runningSnapshot = controller.snapshot()
    assertEquals(ManagedProcessStatus.RUNNING, runningSnapshot.status)
    assertEquals("provider_native", runningSnapshot.metadata["sandboxCommandBackendKind"])
    assertEquals("provider_native", runningSnapshot.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals("provider_native_signal", runningSnapshot.metadata["terminationSupport"])
    assertEquals("true", runningSnapshot.metadata["sandboxCommandSupportsReconnect"])
    assertEquals("true", runningSnapshot.metadata["sandboxCommandSupportsManagedProcessLiveObservation"])
    assertEquals(
      "false",
      runningSnapshot.metadata["sandboxCommandSupportsManagedProcessObservationCursorResume"],
    )
    assertEquals("false", runningSnapshot.metadata["sandboxCommandSupportsManagedProcessObservationBackfill"])
    assertEquals(
      "host_buffered_seed_then_live_attach",
      runningSnapshot.metadata["sandboxCommandProviderObservationResumeContract"],
    )
    assertEquals(
      "envd_connect_request_selector_only",
      runningSnapshot.metadata["sandboxCommandProviderObservationResumeBlocker"],
    )
    assertEquals("provider_native", runningSnapshot.metadata["sandboxTraceBackendKind"])
    assertTrue(!runningSnapshot.metadata["sandboxTraceId"].isNullOrBlank())
    assertTrue(!runningSnapshot.metadata["sandboxTraceBackendSpanId"].isNullOrBlank())
    assertTrue(!runningSnapshot.metadata["sandboxTraceProviderStartSpanId"].isNullOrBlank())
    assertEquals(
      runningSnapshot.metadata["sandboxTraceBackendSpanId"],
      runningSnapshot.metadata["sandboxTraceProviderStartParentSpanId"],
    )
    assertEquals("envd_process", runningSnapshot.metadata["sandboxCommandProviderHandleKind"])
    assertEquals("tag", runningSnapshot.metadata["sandboxCommandProviderStableSelectorKind"])
    assertEquals("proc-native-managed", runningSnapshot.metadata["sandboxCommandProviderStableSelectorValue"])
    assertEquals("tag", runningSnapshot.metadata["sandboxCommandIdKind"])
    assertEquals("proc-native-managed", runningSnapshot.metadata["sandboxCommandId"])
    assertEquals("pid", runningSnapshot.metadata["sandboxCommandProviderLiveSelectorKind"])
    assertEquals("654", runningSnapshot.metadata["sandboxCommandProviderLiveSelectorValue"])
    assertEquals("host_managed_snapshot", runningSnapshot.metadata["sandboxCommandObservationMode"])
    assertEquals(
      "provider_event_stream_host_buffered",
      runningSnapshot.metadata["sandboxCommandProviderObservationMode"],
    )
    assertEquals("tag", runningSnapshot.metadata["sandboxCommandHandleIdKind"])
    assertEquals("proc-native-managed", runningSnapshot.metadata["sandboxCommandHandleId"])
    assertEquals("proc-native-managed", runningSnapshot.metadata["sandboxCommandHandleTag"])
    assertEquals("2", runningSnapshot.metadata["sandboxCommandObservationEventCount"])
    assertEquals("host_seq_2", runningSnapshot.metadata["sandboxCommandObservationCursor"])
    assertEquals("2", runningSnapshot.metadata["sandboxCommandProviderObservationEventCount"])
    assertEquals("envd_seq_2", runningSnapshot.metadata["sandboxCommandProviderObservationCursor"])
    assertEquals("false", runningSnapshot.metadata["sandboxCommandProviderObservationBackfillSupported"])
    assertEquals("7", runningSnapshot.metadata["sandboxCommandObservationStdoutBytes"])
    assertEquals("0", runningSnapshot.metadata["sandboxCommandObservationStderrBytes"])
    assertEquals("654", runningSnapshot.metadata["sandboxCommandPid"])
    assertEquals("booting", runningSnapshot.stdout)
    assertEquals(SandboxProviderId.E2B.wireValue, runningSnapshot.remoteHandle?.provider)
    assertEquals("sandbox-managed-native", runningSnapshot.remoteHandle?.sandboxId)
    assertEquals("proc-native-managed", runningSnapshot.remoteHandle?.stableSelectorValue)
    assertEquals("654", runningSnapshot.remoteHandle?.liveSelectorValue)
    assertEquals(
      "/home/user/opencray/workspace-sticky/sandbox-managed-native/repo",
      runningSnapshot.remoteHandle?.remoteWorkingDirectory,
    )
    assertEquals("host_managed_snapshot", runningSnapshot.observationState?.mode)
    assertEquals(2L, runningSnapshot.observationState?.hostEventCount)
    assertEquals("host_seq_2", runningSnapshot.observationState?.hostCursor)
    assertEquals(7L, runningSnapshot.observationState?.stdoutBytes)
    assertEquals(2L, runningSnapshot.observationState?.providerEventCount)
    assertEquals("envd_seq_2", runningSnapshot.observationState?.providerCursor)
    assertNull(runningSnapshot.reconnectState)

    val terminateSnapshot = controller.terminate()
    waitUntil { transport.unaryRequests.size == 1 }
    assertEquals("true", terminateSnapshot.metadata["terminationRequested"])
    assertTrue(!terminateSnapshot.metadata["sandboxTraceProviderTerminateSpanId"].isNullOrBlank())
    assertEquals(
      terminateSnapshot.metadata["sandboxTraceBackendSpanId"],
      terminateSnapshot.metadata["sandboxTraceProviderTerminateParentSpanId"],
    )
    val sendSignalRequest = transport.unaryRequests.single()
    assertTrue(sendSignalRequest.url.contains("https://49983-sandbox-managed-native.e2b.app/process.Process/SendSignal"))
    val decodedSignalRequest = E2BEnvdProcessProtoCodec.decodeSendSignalRequest(sendSignalRequest.bodyBytes)
    assertEquals("proc-native-managed", decodedSignalRequest.process.tag)
    assertEquals(9, decodedSignalRequest.signal)

    val completedSnapshot = controller.await(5_000L)
    assertEquals(ManagedProcessStatus.CANCELLED, completedSnapshot.status)
    assertEquals(137, completedSnapshot.exitCode)
    assertEquals("CANCELLED", completedSnapshot.errorCode)
    assertEquals("Managed sandbox command terminated.", completedSnapshot.errorMessage)
    assertEquals("true", completedSnapshot.metadata["terminationRequestAccepted"])
    assertEquals("envd_process_send_signal", completedSnapshot.metadata["sandboxCommandTerminateApi"])
    assertEquals("200", completedSnapshot.metadata["sandboxCommandTerminateHttpStatusCode"])
    assertEquals("tag", completedSnapshot.metadata["sandboxCommandTerminateSelectorKind"])
    assertEquals("proc-native-managed", completedSnapshot.metadata["sandboxCommandTerminateSelectorValue"])
    assertEquals("host_managed_snapshot", completedSnapshot.metadata["sandboxCommandObservationMode"])
    assertEquals("3", completedSnapshot.metadata["sandboxCommandObservationEventCount"])
    assertEquals("host_seq_3", completedSnapshot.metadata["sandboxCommandObservationCursor"])
    assertEquals("3", completedSnapshot.metadata["sandboxCommandProviderObservationEventCount"])
    assertEquals("envd_seq_3", completedSnapshot.metadata["sandboxCommandProviderObservationCursor"])
    assertEquals("7", completedSnapshot.metadata["sandboxCommandObservationStdoutBytes"])
    assertEquals("10", completedSnapshot.metadata["sandboxCommandObservationStderrBytes"])
    assertEquals("killed", completedSnapshot.metadata["sandboxCommandNativeProcessStatus"])
    assertEquals("terminated", completedSnapshot.stderr)

    val startRequest = transport.requests.single()
    val decodedStartRequest = E2BEnvdProcessProtoCodec.decodeStartRequest(connectPayload(startRequest.bodyBytes))
    assertEquals("proc-native-managed", decodedStartRequest.tag)
    assertEquals("npm", decodedStartRequest.process.cmd)
    assertEquals(listOf("run", "dev"), decodedStartRequest.process.args)
    assertEquals("/home/user/opencray/workspace-sticky/sandbox-managed-native/repo", decodedStartRequest.process.cwd)
  }

  @Test
  fun nativeManagedProcessReconnectsThroughEnvdConnectAfterRestore() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-managed-reconnect").toPath()
    Files.createDirectories(workspaceRoot.resolve("repo"))
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-managed-reconnect",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect",
        ),
      )
    }
    val streamStarted = CountDownLatch(1)
    val allowStreamComplete = CountDownLatch(1)
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { request, onEnvelope ->
        when {
          request.url.contains("/process.Process/Connect") -> {
            streamStarted.countDown()
            onEnvelope(
              0,
              E2BEnvdProcessProtoCodec.encodeConnectResponse(
                E2BEnvdProcessEvent.Start(pid = 654),
              ),
            )
            onEnvelope(
              0,
              E2BEnvdProcessProtoCodec.encodeConnectResponse(
                E2BEnvdProcessEvent.Data(stdout = " after reconnect".toByteArray(StandardCharsets.UTF_8)),
              ),
            )
            assertTrue(allowStreamComplete.await(5, TimeUnit.SECONDS))
            onEnvelope(
              0,
              E2BEnvdProcessProtoCodec.encodeConnectResponse(
                E2BEnvdProcessEvent.End(
                  exitCode = 0,
                  exited = true,
                  status = "done",
                ),
              ),
            )
            onEnvelope(0x02, "{}".toByteArray(StandardCharsets.UTF_8))
            E2BResponse(statusCode = 200)
          }

          else -> error("Unexpected envd command stream ${request.method} ${request.url}")
        }
      }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = RecordingPythonRuntime(),
      transport = transport,
      json = json,
    )

    val factory = backend.createManagedProcessControllerFactory() as ReconnectableManagedProcessControllerFactory
    val controller = requireNotNull(
      factory.reconnect(
        ManagedProcessSnapshot(
          processId = "proc-native-reconnect",
          taskId = "task-native-reconnect",
          command = "npm",
          args = listOf("run", "dev"),
          workingDirectory = workspaceRoot.resolve("repo").toString(),
          status = ManagedProcessStatus.RUNNING,
          processStarted = true,
          timeoutMs = 5_000L,
          stdout = "booting",
          startedAtEpochMs = 100L,
          updatedAtEpochMs = 100L,
          metadata = mapOf(
            "runtimeKind" to "command_exec",
            "runtimeBackend" to "e2b_envd_native_command",
            "runtimeTransport" to "connect_proto_minimal",
            "sandboxProvider" to SandboxProviderId.E2B.wireValue,
            "sandboxCommandApi" to "envd_process_start",
            "sandboxCommandNativeProtocol" to "envd_connect_process_v1",
            "sandboxCommandBackendKind" to "provider_native",
            "sandboxCommandBackendResolvedKind" to "provider_native",
            "sandboxCommandProviderNative" to "true",
            "sandboxCommandSupportsStreamingLogs" to "false",
            "sandboxCommandSupportsReconnect" to "true",
            "sandboxCommandObservationMode" to "host_managed_snapshot",
            "sandboxCommandPid" to "654",
            "remoteWorkspaceRoot" to "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect",
            "remoteWorkingDirectory" to "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect/repo",
          ),
        ),
      ),
    )

    assertTrue(streamStarted.await(5, TimeUnit.SECONDS))
    waitUntil {
      controller.snapshot().metadata["sandboxCommandReconnectStatus"] == "attached"
    }
    val runningSnapshot = controller.snapshot()
    assertEquals(ManagedProcessStatus.RUNNING, runningSnapshot.status)
    assertEquals("booting after reconnect", runningSnapshot.stdout)
    assertEquals("envd_process_connect", runningSnapshot.metadata["sandboxCommandReconnectApi"])
    assertEquals("durable_registry_restore", runningSnapshot.metadata["sandboxCommandReconnectSource"])
    assertEquals("attached", runningSnapshot.metadata["sandboxCommandReconnectStatus"])
    assertEquals("attached_live", runningSnapshot.metadata["sandboxCommandReconnectRecoveryState"])
    assertNull(runningSnapshot.metadata["sandboxCommandReconnectHttpStatusCode"])
    assertEquals("envd_process", runningSnapshot.metadata["sandboxCommandProviderHandleKind"])
    assertEquals("tag", runningSnapshot.metadata["sandboxCommandProviderStableSelectorKind"])
    assertEquals("proc-native-reconnect", runningSnapshot.metadata["sandboxCommandProviderStableSelectorValue"])
    assertEquals("tag", runningSnapshot.metadata["sandboxCommandIdKind"])
    assertEquals("proc-native-reconnect", runningSnapshot.metadata["sandboxCommandId"])
    assertEquals("pid", runningSnapshot.metadata["sandboxCommandProviderLiveSelectorKind"])
    assertEquals("654", runningSnapshot.metadata["sandboxCommandProviderLiveSelectorValue"])
    assertEquals("pid", runningSnapshot.metadata["sandboxCommandReconnectSelectorKind"])
    assertEquals("654", runningSnapshot.metadata["sandboxCommandReconnectSelectorValue"])
    assertEquals("snapshot_pid", runningSnapshot.metadata["sandboxCommandReconnectSelectorSource"])
    assertEquals("tag", runningSnapshot.metadata["sandboxCommandHandleIdKind"])
    assertEquals("proc-native-reconnect", runningSnapshot.metadata["sandboxCommandHandleId"])
    assertEquals("proc-native-reconnect", runningSnapshot.metadata["sandboxCommandHandleTag"])
    assertEquals("3", runningSnapshot.metadata["sandboxCommandObservationEventCount"])
    assertEquals("host_seq_3", runningSnapshot.metadata["sandboxCommandObservationCursor"])
    assertEquals("23", runningSnapshot.metadata["sandboxCommandObservationStdoutBytes"])
    assertEquals("0", runningSnapshot.metadata["sandboxCommandObservationStderrBytes"])
    assertEquals(
      "observation_snapshot_then_live_attach",
      runningSnapshot.metadata["sandboxCommandReconnectResumeMode"],
    )
    assertEquals("false", runningSnapshot.metadata["sandboxCommandReconnectBackfillSupported"])
    assertEquals("true", runningSnapshot.metadata["sandboxCommandReconnectOutputGapRisk"])
    assertEquals(
      "observation_snapshot_metadata",
      runningSnapshot.metadata["sandboxCommandReconnectSeedSource"],
    )
    assertEquals("true", runningSnapshot.metadata["sandboxCommandReconnectProviderObservationSeedConsumed"])
    assertEquals(
      "consumed_live_attach",
      runningSnapshot.metadata["sandboxCommandReconnectProviderObservationSeedState"],
    )
    assertEquals("host_seq_1", runningSnapshot.metadata["sandboxCommandReconnectSeedObservationCursor"])
    assertEquals(
      "envd_seq_1",
      runningSnapshot.metadata["sandboxCommandReconnectSeedProviderObservationCursor"],
    )
    assertEquals("1", runningSnapshot.metadata["sandboxCommandReconnectSeedEventCount"])
    assertEquals(
      "1",
      runningSnapshot.metadata["sandboxCommandReconnectSeedProviderObservationEventCount"],
    )
    assertEquals("7", runningSnapshot.metadata["sandboxCommandReconnectSeededStdoutBytes"])
    assertEquals("0", runningSnapshot.metadata["sandboxCommandReconnectSeededStderrBytes"])
    assertEquals(
      "provider_event_stream_host_buffered",
      runningSnapshot.metadata["sandboxCommandProviderObservationMode"],
    )
    assertEquals("3", runningSnapshot.metadata["sandboxCommandProviderObservationEventCount"])
    assertEquals("envd_seq_3", runningSnapshot.metadata["sandboxCommandProviderObservationCursor"])
    val lastAttachedAtEpochMs =
      requireNotNull(runningSnapshot.metadata["sandboxCommandReconnectLastAttachedAtEpochMs"]).toLong()
    val lastEventAtEpochMs =
      requireNotNull(runningSnapshot.metadata["sandboxCommandReconnectLastEventAtEpochMs"]).toLong()
    val seedConsumedAtEpochMs =
      requireNotNull(runningSnapshot.metadata["sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs"]).toLong()
    assertTrue(lastAttachedAtEpochMs >= runningSnapshot.startedAtEpochMs)
    assertTrue(lastEventAtEpochMs >= lastAttachedAtEpochMs)
    assertTrue(seedConsumedAtEpochMs >= runningSnapshot.startedAtEpochMs)
    assertEquals("data", runningSnapshot.metadata["sandboxCommandReconnectLastEventKind"])
    assertEquals("true", runningSnapshot.metadata["sandboxCommandSupportsReconnect"])
    assertEquals("true", runningSnapshot.metadata["sandboxCommandSupportsManagedProcessLiveObservation"])
    assertEquals(
      "false",
      runningSnapshot.metadata["sandboxCommandSupportsManagedProcessObservationCursorResume"],
    )
    assertEquals("false", runningSnapshot.metadata["sandboxCommandSupportsManagedProcessObservationBackfill"])
    assertEquals(
      "host_buffered_seed_then_live_attach",
      runningSnapshot.metadata["sandboxCommandProviderObservationResumeContract"],
    )
    assertEquals(
      "envd_connect_request_selector_only",
      runningSnapshot.metadata["sandboxCommandProviderObservationResumeBlocker"],
    )
    assertTrue(!runningSnapshot.metadata["sandboxTraceId"].isNullOrBlank())
    assertTrue(!runningSnapshot.metadata["sandboxTraceReconnectSpanId"].isNullOrBlank())
    assertTrue(!runningSnapshot.metadata["sandboxTraceBackendSpanId"].isNullOrBlank())
    assertEquals(
      runningSnapshot.metadata["sandboxTraceReconnectSpanId"],
      runningSnapshot.metadata["sandboxTraceBackendParentSpanId"],
    )
    assertTrue(!runningSnapshot.metadata["sandboxTraceProviderConnectSpanId"].isNullOrBlank())
    assertEquals(
      runningSnapshot.metadata["sandboxTraceReconnectSpanId"],
      runningSnapshot.metadata["sandboxTraceProviderConnectParentSpanId"],
    )

    allowStreamComplete.countDown()
    val completedSnapshot = controller.await(5_000L)
    assertEquals(ManagedProcessStatus.SUCCESS, completedSnapshot.status)
    assertEquals(0, completedSnapshot.exitCode)
    assertEquals("completed", completedSnapshot.metadata["sandboxCommandReconnectStatus"])
    assertEquals("completed", completedSnapshot.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("200", completedSnapshot.metadata["sandboxCommandReconnectHttpStatusCode"])
    assertEquals("true", completedSnapshot.metadata["sandboxCommandReconnectOutputGapRisk"])
    assertEquals(
      "observation_snapshot_metadata",
      completedSnapshot.metadata["sandboxCommandReconnectSeedSource"],
    )
    assertEquals("true", completedSnapshot.metadata["sandboxCommandReconnectProviderObservationSeedConsumed"])
    assertEquals(
      "consumed_live_attach",
      completedSnapshot.metadata["sandboxCommandReconnectProviderObservationSeedState"],
    )
    assertEquals("4", completedSnapshot.metadata["sandboxCommandObservationEventCount"])
    assertEquals("host_seq_4", completedSnapshot.metadata["sandboxCommandObservationCursor"])
    assertEquals("4", completedSnapshot.metadata["sandboxCommandProviderObservationEventCount"])
    assertEquals("envd_seq_4", completedSnapshot.metadata["sandboxCommandProviderObservationCursor"])

    val connectRequest = transport.requests.single()
    assertTrue(connectRequest.url.contains("https://49983-sandbox-managed-reconnect.e2b.app/process.Process/Connect"))
    val decodedConnectRequest = E2BEnvdProcessProtoCodec.decodeConnectRequest(connectPayload(connectRequest.bodyBytes))
    assertEquals(654, decodedConnectRequest.process.pid)
  }

  @Test
  fun nativeManagedProcessReconnectUsesTypedStateWhenMetadataIsSparse() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-managed-reconnect-typed-state").toPath()
    Files.createDirectories(workspaceRoot.resolve("repo"))
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-managed-reconnect-typed-state",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = null,
        ),
      )
    }
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { request, onEnvelope ->
        when {
          request.url.contains("/process.Process/Connect") -> {
            onEnvelope(
              0,
              E2BEnvdProcessProtoCodec.encodeConnectResponse(
                E2BEnvdProcessEvent.Data(stdout = " after reconnect".toByteArray(StandardCharsets.UTF_8)),
              ),
            )
            onEnvelope(
              0,
              E2BEnvdProcessProtoCodec.encodeConnectResponse(
                E2BEnvdProcessEvent.End(
                  exitCode = 0,
                  exited = true,
                  status = "done",
                ),
              ),
            )
            onEnvelope(0x02, "{}".toByteArray(StandardCharsets.UTF_8))
            E2BResponse(statusCode = 200)
          }

          else -> error("Unexpected envd command stream ${request.method} ${request.url}")
        }
      }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = RecordingPythonRuntime(),
      transport = transport,
      json = json,
    )

    val factory = backend.createManagedProcessControllerFactory() as ReconnectableManagedProcessControllerFactory
    val controller = requireNotNull(
      factory.reconnect(
        ManagedProcessSnapshot(
          processId = "proc-native-reconnect-typed-state",
          taskId = "task-native-reconnect-typed-state",
          command = "npm",
          args = listOf("run", "dev"),
          workingDirectory = workspaceRoot.resolve("repo").toString(),
          status = ManagedProcessStatus.RUNNING,
          processStarted = true,
          timeoutMs = 5_000L,
          stdout = "booting",
          startedAtEpochMs = 100L,
          updatedAtEpochMs = 100L,
          remoteHandle = ManagedProcessRemoteHandle(
            provider = SandboxProviderId.E2B.wireValue,
            sandboxId = "sandbox-managed-reconnect-typed-state",
            sandboxDomain = "e2b.app",
            commandIdKind = "tag",
            commandId = "proc-native-reconnect-typed-state",
            providerHandleKind = "envd_process",
            stableSelectorKind = "tag",
            stableSelectorValue = "proc-native-reconnect-typed-state",
            liveSelectorKind = "pid",
            liveSelectorValue = "654",
            remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-typed-state",
            remoteWorkingDirectory = "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-typed-state/repo",
            nativeProtocol = "envd_connect_process_v1",
          ),
          observationState = ManagedProcessObservationState(
            mode = "host_managed_snapshot",
            hostEventCount = 1L,
            hostCursor = "host_seq_1",
            stdoutBytes = 7L,
            stderrBytes = 0L,
            providerMode = "provider_event_stream_host_buffered",
            providerEventCount = 1L,
            providerCursor = "envd_seq_1",
            providerBackfillSupported = false,
            liveObservationSupported = true,
            cursorResumeSupported = false,
            backfillSupported = false,
          ),
          reconnectState = ManagedProcessReconnectState(
            attemptCount = 2,
            selectorKind = "pid",
            selectorValue = "654",
            selectorSource = "snapshot_pid",
            seed = ManagedProcessReconnectSeed(
              source = "durable_snapshot_metadata",
              hostObservationCursor = "host_seq_1",
              hostObservationEventCount = 1L,
              stdoutBytes = 7L,
              stderrBytes = 0L,
              providerObservationCursor = "envd_seq_1",
              providerObservationEventCount = 1L,
            ),
          ),
          metadata = emptyMap(),
        ),
      ),
    )

    val completedSnapshot = controller.await(5_000L)

    assertEquals(ManagedProcessStatus.SUCCESS, completedSnapshot.status)
    assertEquals("booting after reconnect", completedSnapshot.stdout)
    assertEquals(
      "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-typed-state",
      completedSnapshot.metadata["remoteWorkspaceRoot"],
    )
    assertEquals(
      "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-typed-state/repo",
      completedSnapshot.metadata["remoteWorkingDirectory"],
    )
    assertEquals("provider_native", completedSnapshot.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals("envd_process_connect", completedSnapshot.metadata["sandboxCommandReconnectApi"])
    assertEquals("3", completedSnapshot.metadata["sandboxCommandReconnectAttemptCount"])
    assertEquals("pid", completedSnapshot.metadata["sandboxCommandReconnectSelectorKind"])
    assertEquals("654", completedSnapshot.metadata["sandboxCommandReconnectSelectorValue"])
    assertEquals("snapshot_pid", completedSnapshot.metadata["sandboxCommandReconnectSelectorSource"])
    assertEquals("host_seq_1", completedSnapshot.metadata["sandboxCommandReconnectSeedObservationCursor"])
    assertEquals("7", completedSnapshot.metadata["sandboxCommandReconnectSeededStdoutBytes"])
    assertEquals("envd_seq_1", completedSnapshot.metadata["sandboxCommandReconnectSeedProviderObservationCursor"])
    assertEquals(
      "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-typed-state/repo",
      completedSnapshot.remoteHandle?.remoteWorkingDirectory,
    )
    assertEquals("654", completedSnapshot.remoteHandle?.liveSelectorValue)
    assertEquals(3L, completedSnapshot.observationState?.hostEventCount)
    assertEquals("host_seq_3", completedSnapshot.observationState?.hostCursor)
    assertEquals(23L, completedSnapshot.observationState?.stdoutBytes)
    assertEquals(3, completedSnapshot.reconnectState?.attemptCount)
    assertEquals("completed", completedSnapshot.reconnectState?.recoveryState)
    assertEquals("durable_snapshot_metadata", completedSnapshot.reconnectState?.seed?.source)
    assertEquals("host_seq_1", completedSnapshot.reconnectState?.seed?.hostObservationCursor)
    assertEquals("envd_seq_1", completedSnapshot.reconnectState?.seed?.providerObservationCursor)

    val connectRequest = transport.requests.single()
    val decodedConnectRequest = E2BEnvdProcessProtoCodec.decodeConnectRequest(connectPayload(connectRequest.bodyBytes))
    assertEquals(654, decodedConnectRequest.process.pid)
    assertNull(decodedConnectRequest.process.tag)
  }

  @Test
  fun nativeManagedProcessReconnectPrefersDeliveredObservationStateAsSeedWhenPresent() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-managed-reconnect-delivered-seed").toPath()
    Files.createDirectories(workspaceRoot.resolve("repo"))
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-managed-reconnect-delivered-seed",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-delivered-seed",
        ),
      )
    }
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { request, onEnvelope ->
        when {
          request.url.contains("/process.Process/Connect") -> {
            onEnvelope(
              0,
              E2BEnvdProcessProtoCodec.encodeConnectResponse(
                E2BEnvdProcessEvent.Data(stdout = " after reconnect".toByteArray(StandardCharsets.UTF_8)),
              ),
            )
            onEnvelope(
              0,
              E2BEnvdProcessProtoCodec.encodeConnectResponse(
                E2BEnvdProcessEvent.End(
                  exitCode = 0,
                  exited = true,
                  status = "done",
                ),
              ),
            )
            onEnvelope(0x02, "{}".toByteArray(StandardCharsets.UTF_8))
            E2BResponse(statusCode = 200)
          }

          else -> error("Unexpected envd command stream ${request.method} ${request.url}")
        }
      }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = RecordingPythonRuntime(),
      transport = transport,
      json = json,
    )

    val factory = backend.createManagedProcessControllerFactory() as ReconnectableManagedProcessControllerFactory
    val controller = requireNotNull(
      factory.reconnect(
        ManagedProcessSnapshot(
          processId = "proc-native-reconnect-delivered-seed",
          taskId = "task-native-reconnect-delivered-seed",
          command = "npm",
          args = listOf("run", "dev"),
          workingDirectory = workspaceRoot.resolve("repo").toString(),
          status = ManagedProcessStatus.RUNNING,
          processStarted = true,
          timeoutMs = 5_000L,
          stdout = "booting",
          startedAtEpochMs = 100L,
          updatedAtEpochMs = 100L,
          remoteHandle = ManagedProcessRemoteHandle(
            provider = SandboxProviderId.E2B.wireValue,
            sandboxId = "sandbox-managed-reconnect-delivered-seed",
            sandboxDomain = "e2b.app",
            commandIdKind = "tag",
            commandId = "proc-native-reconnect-delivered-seed",
            providerHandleKind = "envd_process",
            stableSelectorKind = "tag",
            stableSelectorValue = "proc-native-reconnect-delivered-seed",
            liveSelectorKind = "pid",
            liveSelectorValue = "654",
            remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-delivered-seed",
            remoteWorkingDirectory = "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-delivered-seed/repo",
            nativeProtocol = "envd_connect_process_v1",
          ),
          observationState = ManagedProcessObservationState(
            mode = "host_managed_snapshot",
            hostEventCount = 1L,
            hostCursor = "host_seq_1",
            stdoutBytes = 7L,
            stderrBytes = 0L,
            providerMode = "provider_event_stream_host_buffered",
            providerEventCount = 1L,
            providerCursor = "envd_seq_1",
            providerBackfillSupported = false,
            liveObservationSupported = true,
            cursorResumeSupported = false,
            backfillSupported = false,
          ),
          reconnectState = ManagedProcessReconnectState(
            attemptCount = 2,
            selectorKind = "pid",
            selectorValue = "654",
            selectorSource = "snapshot_pid",
            seed = ManagedProcessReconnectSeed(
              source = "durable_snapshot_metadata",
              hostObservationCursor = "host_seq_1",
              hostObservationEventCount = 1L,
              stdoutBytes = 7L,
              stderrBytes = 0L,
              providerObservationCursor = "envd_seq_1",
              providerObservationEventCount = 1L,
            ),
          ),
          deliveredObservationState = ManagedProcessDeliveredObservationState(
            mode = "host_managed_snapshot",
            cursor = "host_seq_2",
            stdoutBytes = 14L,
            stderrBytes = 0L,
            providerMode = "provider_event_stream_host_buffered",
            providerCursor = "envd_seq_2",
            providerEventCount = 2L,
            deliveredAtEpochMs = 150L,
          ),
          metadata = emptyMap(),
        ),
      ),
    )

    val completedSnapshot = controller.await(5_000L)

    assertEquals(ManagedProcessStatus.SUCCESS, completedSnapshot.status)
    assertEquals(
      "durable_delivered_observation_state",
      completedSnapshot.metadata["sandboxCommandReconnectSeedSource"],
    )
    assertEquals(
      "delivered_seed_then_live_attach",
      completedSnapshot.metadata["sandboxCommandReconnectResumeMode"],
    )
    assertEquals("host_seq_2", completedSnapshot.metadata["sandboxCommandReconnectSeedObservationCursor"])
    assertEquals("2", completedSnapshot.metadata["sandboxCommandReconnectSeedEventCount"])
    assertEquals("14", completedSnapshot.metadata["sandboxCommandReconnectSeededStdoutBytes"])
    assertEquals(
      "envd_seq_2",
      completedSnapshot.metadata["sandboxCommandReconnectSeedProviderObservationCursor"],
    )
    assertEquals(
      "2",
      completedSnapshot.metadata["sandboxCommandReconnectSeedProviderObservationEventCount"],
    )
    assertEquals(
      "durable_delivered_observation_state",
      completedSnapshot.metadata["sandboxCommandReconnectProviderObservationSeedSource"],
    )
    assertEquals(
      "false",
      completedSnapshot.metadata["sandboxCommandReconnectProviderObservationResumeApplied"],
    )
    assertEquals(
      "protocol_cursor_resume_unsupported",
      completedSnapshot.metadata["sandboxCommandReconnectProviderObservationResumeReason"],
    )
    assertEquals(
      "durable_delivered_observation_state",
      completedSnapshot.reconnectState?.seed?.source,
    )
    assertEquals("host_seq_2", completedSnapshot.reconnectState?.seed?.hostObservationCursor)
    assertEquals("envd_seq_2", completedSnapshot.reconnectState?.seed?.providerObservationCursor)
    assertEquals(
      "durable_delivered_observation_state",
      completedSnapshot.reconnectState?.seed?.providerObservationSeedSource,
    )
    assertEquals(false, completedSnapshot.reconnectState?.providerObservationResumeApplied)
    assertEquals(
      "protocol_cursor_resume_unsupported",
      completedSnapshot.reconnectState?.providerObservationResumeReason,
    )

    val connectRequest = transport.requests.single()
    val decodedConnectRequest = E2BEnvdProcessProtoCodec.decodeConnectRequest(connectPayload(connectRequest.bodyBytes))
    assertEquals(654, decodedConnectRequest.process.pid)
    assertNull(decodedConnectRequest.process.tag)
  }

  @Test
  fun nativeManagedProcessReconnectMarksAttachedOnFirstLiveDataEventWithoutStart() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-managed-reconnect-data-first").toPath()
    Files.createDirectories(workspaceRoot.resolve("repo"))
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-managed-reconnect-data-first",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-data-first",
        ),
      )
    }
    val streamStarted = CountDownLatch(1)
    val allowStreamComplete = CountDownLatch(1)
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { request, onEnvelope ->
        when {
          request.url.contains("/process.Process/Connect") -> {
            streamStarted.countDown()
            onEnvelope(
              0,
              E2BEnvdProcessProtoCodec.encodeConnectResponse(
                E2BEnvdProcessEvent.Data(stdout = " live output".toByteArray(StandardCharsets.UTF_8)),
              ),
            )
            assertTrue(allowStreamComplete.await(5, TimeUnit.SECONDS))
            onEnvelope(
              0,
              E2BEnvdProcessProtoCodec.encodeConnectResponse(
                E2BEnvdProcessEvent.End(
                  exitCode = 0,
                  exited = true,
                  status = "done",
                ),
              ),
            )
            onEnvelope(0x02, "{}".toByteArray(StandardCharsets.UTF_8))
            E2BResponse(statusCode = 200)
          }

          else -> error("Unexpected envd command stream ${request.method} ${request.url}")
        }
      }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = RecordingPythonRuntime(),
      transport = transport,
      json = json,
    )

    val factory = backend.createManagedProcessControllerFactory() as ReconnectableManagedProcessControllerFactory
    val controller = requireNotNull(
      factory.reconnect(
        ManagedProcessSnapshot(
          processId = "proc-native-reconnect-data-first",
          taskId = "task-native-reconnect-data-first",
          command = "npm",
          args = listOf("run", "dev"),
          workingDirectory = workspaceRoot.resolve("repo").toString(),
          status = ManagedProcessStatus.RUNNING,
          processStarted = true,
          timeoutMs = 5_000L,
          stdout = "booting",
          startedAtEpochMs = 100L,
          updatedAtEpochMs = 100L,
          metadata = mapOf(
            "runtimeKind" to "command_exec",
            "runtimeBackend" to "e2b_envd_native_command",
            "runtimeTransport" to "connect_proto_minimal",
            "sandboxProvider" to SandboxProviderId.E2B.wireValue,
            "sandboxCommandApi" to "envd_process_start",
            "sandboxCommandNativeProtocol" to "envd_connect_process_v1",
            "sandboxCommandBackendKind" to "provider_native",
            "sandboxCommandBackendResolvedKind" to "provider_native",
            "sandboxCommandProviderNative" to "true",
            "sandboxCommandSupportsStreamingLogs" to "false",
            "sandboxCommandSupportsReconnect" to "true",
            "sandboxCommandObservationMode" to "host_managed_snapshot",
            "sandboxCommandPid" to "654",
            "remoteWorkspaceRoot" to "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-data-first",
            "remoteWorkingDirectory" to "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-data-first/repo",
          ),
        ),
      ),
    )

    assertTrue(streamStarted.await(5, TimeUnit.SECONDS))
    waitUntil {
      controller.snapshot().metadata["sandboxCommandReconnectStatus"] == "attached"
    }
    val runningSnapshot = controller.snapshot()
    assertEquals(ManagedProcessStatus.RUNNING, runningSnapshot.status)
    assertEquals("booting live output", runningSnapshot.stdout)
    assertEquals("attached", runningSnapshot.metadata["sandboxCommandReconnectStatus"])
    assertEquals("attached_live", runningSnapshot.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("envd_process", runningSnapshot.metadata["sandboxCommandProviderHandleKind"])
    assertEquals("pid", runningSnapshot.metadata["sandboxCommandProviderLiveSelectorKind"])
    assertEquals("654", runningSnapshot.metadata["sandboxCommandProviderLiveSelectorValue"])
    assertEquals("pid", runningSnapshot.metadata["sandboxCommandReconnectSelectorKind"])
    assertEquals("654", runningSnapshot.metadata["sandboxCommandReconnectSelectorValue"])
    assertEquals("snapshot_pid", runningSnapshot.metadata["sandboxCommandReconnectSelectorSource"])
    assertEquals("2", runningSnapshot.metadata["sandboxCommandObservationEventCount"])
    assertEquals("host_seq_2", runningSnapshot.metadata["sandboxCommandObservationCursor"])
    assertEquals("19", runningSnapshot.metadata["sandboxCommandObservationStdoutBytes"])
    assertEquals(
      "observation_snapshot_metadata",
      runningSnapshot.metadata["sandboxCommandReconnectSeedSource"],
    )
    assertEquals("true", runningSnapshot.metadata["sandboxCommandReconnectProviderObservationSeedConsumed"])
    assertEquals(
      "consumed_live_attach",
      runningSnapshot.metadata["sandboxCommandReconnectProviderObservationSeedState"],
    )
    assertEquals("host_seq_1", runningSnapshot.metadata["sandboxCommandReconnectSeedObservationCursor"])
    assertEquals(
      "envd_seq_1",
      runningSnapshot.metadata["sandboxCommandReconnectSeedProviderObservationCursor"],
    )
    assertEquals("1", runningSnapshot.metadata["sandboxCommandReconnectSeedEventCount"])
    assertEquals(
      "1",
      runningSnapshot.metadata["sandboxCommandReconnectSeedProviderObservationEventCount"],
    )
    assertEquals("data", runningSnapshot.metadata["sandboxCommandReconnectLastEventKind"])
    assertNotNull(runningSnapshot.metadata["sandboxCommandReconnectLastAttachedAtEpochMs"])
    assertNotNull(runningSnapshot.metadata["sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs"])
    assertEquals("2", runningSnapshot.metadata["sandboxCommandProviderObservationEventCount"])
    assertEquals("envd_seq_2", runningSnapshot.metadata["sandboxCommandProviderObservationCursor"])

    allowStreamComplete.countDown()
    val completedSnapshot = controller.await(5_000L)
    assertEquals(ManagedProcessStatus.SUCCESS, completedSnapshot.status)
    assertEquals("completed", completedSnapshot.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("3", completedSnapshot.metadata["sandboxCommandObservationEventCount"])
    assertEquals("host_seq_3", completedSnapshot.metadata["sandboxCommandObservationCursor"])
    assertEquals("3", completedSnapshot.metadata["sandboxCommandProviderObservationEventCount"])
    assertEquals("envd_seq_3", completedSnapshot.metadata["sandboxCommandProviderObservationCursor"])
  }

  @Test
  fun nativeManagedProcessReconnectFallsBackToTagSelectorWhenPidMissing() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-managed-reconnect-tag-fallback").toPath()
    Files.createDirectories(workspaceRoot.resolve("repo"))
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-managed-reconnect-tag-fallback",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-tag-fallback",
        ),
      )
    }
    val streamStarted = CountDownLatch(1)
    val allowStreamComplete = CountDownLatch(1)
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { request, onEnvelope ->
        when {
          request.url.contains("/process.Process/Connect") -> {
            streamStarted.countDown()
            onEnvelope(
              0,
              E2BEnvdProcessProtoCodec.encodeConnectResponse(
                E2BEnvdProcessEvent.Start(pid = 777),
              ),
            )
            assertTrue(allowStreamComplete.await(5, TimeUnit.SECONDS))
            onEnvelope(
              0,
              E2BEnvdProcessProtoCodec.encodeConnectResponse(
                E2BEnvdProcessEvent.End(
                  exitCode = 0,
                  exited = true,
                  status = "done",
                ),
              ),
            )
            onEnvelope(0x02, "{}".toByteArray(StandardCharsets.UTF_8))
            E2BResponse(statusCode = 200)
          }

          else -> error("Unexpected envd command stream ${request.method} ${request.url}")
        }
      }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = RecordingPythonRuntime(),
      transport = transport,
      json = json,
    )

    val factory = backend.createManagedProcessControllerFactory() as ReconnectableManagedProcessControllerFactory
    val controller = requireNotNull(
      factory.reconnect(
        ManagedProcessSnapshot(
          processId = "proc-native-reconnect-tag-fallback",
          taskId = "task-native-reconnect-tag-fallback",
          command = "npm",
          args = listOf("run", "dev"),
          workingDirectory = workspaceRoot.resolve("repo").toString(),
          status = ManagedProcessStatus.RUNNING,
          processStarted = true,
          timeoutMs = 5_000L,
          stdout = "booting",
          startedAtEpochMs = 100L,
          updatedAtEpochMs = 100L,
          metadata = mapOf(
            "runtimeKind" to "command_exec",
            "runtimeBackend" to "e2b_envd_native_command",
            "runtimeTransport" to "connect_proto_minimal",
            "sandboxProvider" to SandboxProviderId.E2B.wireValue,
            "sandboxCommandApi" to "envd_process_start",
            "sandboxCommandNativeProtocol" to "envd_connect_process_v1",
            "sandboxCommandBackendKind" to "provider_native",
            "sandboxCommandBackendResolvedKind" to "provider_native",
            "sandboxCommandProviderNative" to "true",
            "sandboxCommandSupportsStreamingLogs" to "false",
            "sandboxCommandSupportsReconnect" to "true",
            "sandboxCommandObservationMode" to "host_managed_snapshot",
            "remoteWorkspaceRoot" to "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-tag-fallback",
            "remoteWorkingDirectory" to "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-tag-fallback/repo",
          ),
        ),
      ),
    )

    assertTrue(streamStarted.await(5, TimeUnit.SECONDS))
    waitUntil {
      controller.snapshot().metadata["sandboxCommandReconnectStatus"] == "attached"
    }
    val runningSnapshot = controller.snapshot()
    assertEquals("tag", runningSnapshot.metadata["sandboxCommandReconnectSelectorKind"])
    assertEquals("proc-native-reconnect-tag-fallback", runningSnapshot.metadata["sandboxCommandReconnectSelectorValue"])
    assertEquals("stable_tag", runningSnapshot.metadata["sandboxCommandReconnectSelectorSource"])
    assertEquals("true", runningSnapshot.metadata["sandboxCommandSupportsManagedProcessLiveObservation"])
    assertEquals(
      "false",
      runningSnapshot.metadata["sandboxCommandSupportsManagedProcessObservationCursorResume"],
    )
    assertEquals("false", runningSnapshot.metadata["sandboxCommandSupportsManagedProcessObservationBackfill"])
    assertEquals(
      "observation_snapshot_metadata",
      runningSnapshot.metadata["sandboxCommandReconnectSeedSource"],
    )
    assertEquals("true", runningSnapshot.metadata["sandboxCommandReconnectProviderObservationSeedConsumed"])
    assertEquals(
      "consumed_live_attach",
      runningSnapshot.metadata["sandboxCommandReconnectProviderObservationSeedState"],
    )
    assertEquals("pid", runningSnapshot.metadata["sandboxCommandProviderLiveSelectorKind"])
    assertEquals("777", runningSnapshot.metadata["sandboxCommandProviderLiveSelectorValue"])

    allowStreamComplete.countDown()
    val completedSnapshot = controller.await(5_000L)
    assertEquals(ManagedProcessStatus.SUCCESS, completedSnapshot.status)

    val connectRequest = transport.requests.single()
    val decodedConnectRequest = E2BEnvdProcessProtoCodec.decodeConnectRequest(connectPayload(connectRequest.bodyBytes))
    assertNull(decodedConnectRequest.process.pid)
    assertEquals("proc-native-reconnect-tag-fallback", decodedConnectRequest.process.tag)
  }

  @Test
  fun nativeManagedProcessReconnectHttpFailureBeforeAttachBecomesTerminalRecoveryFailure() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-managed-reconnect-terminal-failure").toPath()
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-managed-reconnect-terminal-failure",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-terminal-failure",
        ),
      )
    }
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { request, _ ->
        when {
          request.url.contains("/process.Process/Connect") ->
            E2BResponse(statusCode = 404, body = "process not found")
          else -> error("Unexpected stream request ${request.url}")
        }
      }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = RecordingPythonRuntime(),
      transport = transport,
      json = json,
    )

    val factory = backend.createManagedProcessControllerFactory() as ReconnectableManagedProcessControllerFactory
    val controller = requireNotNull(
      factory.reconnect(
        ManagedProcessSnapshot(
          processId = "proc-native-reconnect-terminal-failure",
          taskId = "task-native-reconnect-terminal-failure",
          command = "npm",
          args = listOf("run", "dev"),
          workingDirectory = workspaceRoot.resolve("repo").toString(),
          status = ManagedProcessStatus.RUNNING,
          processStarted = true,
          timeoutMs = 5_000L,
          stdout = "booting",
          startedAtEpochMs = 100L,
          updatedAtEpochMs = 100L,
          metadata = mapOf(
            "runtimeKind" to "command_exec",
            "runtimeBackend" to "e2b_envd_native_command",
            "runtimeTransport" to "connect_proto_minimal",
            "sandboxProvider" to SandboxProviderId.E2B.wireValue,
            "sandboxCommandApi" to "envd_process_start",
            "sandboxCommandNativeProtocol" to "envd_connect_process_v1",
            "sandboxCommandBackendKind" to "provider_native",
            "sandboxCommandBackendResolvedKind" to "provider_native",
            "sandboxCommandProviderNative" to "true",
            "sandboxCommandSupportsStreamingLogs" to "false",
            "sandboxCommandSupportsReconnect" to "true",
            "sandboxCommandObservationMode" to "host_managed_snapshot",
            "sandboxCommandPid" to "654",
            "remoteWorkspaceRoot" to "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-terminal-failure",
            "remoteWorkingDirectory" to "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-terminal-failure/repo",
          ),
        ),
      ),
    )

    val snapshot = controller.await(5_000L)

    assertEquals(ManagedProcessStatus.FAILED, snapshot.status)
    assertEquals("PROCESS_RECONNECT_FAILED", snapshot.errorCode)
    assertEquals("failed", snapshot.metadata["sandboxCommandReconnectStatus"])
    assertEquals("failed_terminal", snapshot.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("false", snapshot.metadata["sandboxCommandReconnectRetryable"])
    assertEquals("404", snapshot.metadata["sandboxCommandReconnectHttpStatusCode"])
    assertEquals("http_response_non_success", snapshot.metadata["sandboxCommandReconnectFailureStage"])
    assertEquals(
      "observation_snapshot_metadata",
      snapshot.metadata["sandboxCommandReconnectSeedSource"],
    )
    assertEquals("false", snapshot.metadata["sandboxCommandReconnectProviderObservationSeedConsumed"])
    assertEquals(
      "failed_terminal_before_live_attach",
      snapshot.metadata["sandboxCommandReconnectProviderObservationSeedState"],
    )
    assertNull(snapshot.metadata["sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs"])
    assertNull(snapshot.metadata["sandboxCommandReconnectLastAttachedAtEpochMs"])
  }

  @Test
  fun nativeManagedProcessFallsBackToPythonWrapperWhenRemoteWorkspaceRootIsMissing() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-managed-fallback").toPath()
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-managed-fallback",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = null,
        ),
      )
    }
    val pythonRuntime = RecordingPythonRuntime(
      result = ExecutionResult(
        taskId = "task-managed-wrapper",
        status = ExecutionStatus.SUCCESS,
        exitCode = 0,
        stdout = encodedWrapperPayload(
          CommandWrapperResultPayload(
            exitCode = 0,
            stdout = "wrapper managed stdout",
            stderr = "",
            processStarted = true,
          ),
        ),
        stderr = "",
        startedAtEpochMs = 100L,
        finishedAtEpochMs = 200L,
        metadata = mapOf("runtimeBackend" to "e2b_code_interpreter"),
      ),
    )
    val transport = FakeEnvdCommandTransport()
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = pythonRuntime,
      transport = transport,
      json = json,
    )

    val controller = backend.createManagedProcessControllerFactory().start(
      com.opencray.runtime.process.ManagedProcessStartRequest(
        processId = "proc-managed-wrapper",
        taskId = "task-managed-wrapper",
        command = "git",
        args = listOf("status"),
        workingDirectory = workspaceRoot.toString(),
        timeoutMs = 5_000L,
        requestedAtEpochMs = 100L,
      ),
    )

    val snapshot = controller.await(5_000L)
    assertEquals(ManagedProcessStatus.SUCCESS, snapshot.status)
    assertEquals("wrapper managed stdout", snapshot.stdout)
    assertEquals("python_wrapper", snapshot.metadata["sandboxCommandBackendKind"])
    assertEquals("python_wrapper", snapshot.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals(
      "native_command_remote_workspace_unavailable",
      snapshot.metadata["sandboxCommandBackendFallbackReasonCode"],
    )
    assertTrue(transport.requests.isEmpty())
    assertTrue(transport.unaryRequests.isEmpty())
    assertTrue(pythonRuntime.requests.isNotEmpty())
  }

  @Test
  fun nativeManagedProcessReconnectTransportFailureStaysRunningAndMarkedRetryable() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-managed-reconnect-retryable").toPath()
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-managed-reconnect-retryable",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-retryable",
        ),
      )
    }
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { request, _ ->
        when {
          request.url.contains("/process.Process/Connect") ->
            throw EOFException("socket closed during reconnect")
          else -> error("Unexpected stream request ${request.url}")
        }
      }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = RecordingPythonRuntime(),
      transport = transport,
      json = json,
    )

    val factory = backend.createManagedProcessControllerFactory() as ReconnectableManagedProcessControllerFactory
    val controller = requireNotNull(
      factory.reconnect(
        ManagedProcessSnapshot(
          processId = "proc-native-reconnect-retryable",
          taskId = "task-native-reconnect-retryable",
          command = "npm",
          args = listOf("run", "dev"),
          workingDirectory = workspaceRoot.resolve("repo").toString(),
          status = ManagedProcessStatus.RUNNING,
          processStarted = true,
          timeoutMs = 5_000L,
          stdout = "booting",
          startedAtEpochMs = 100L,
          updatedAtEpochMs = 100L,
          metadata = mapOf(
            "runtimeKind" to "command_exec",
            "runtimeBackend" to "e2b_envd_native_command",
            "runtimeTransport" to "connect_proto_minimal",
            "sandboxProvider" to SandboxProviderId.E2B.wireValue,
            "sandboxCommandApi" to "envd_process_start",
            "sandboxCommandNativeProtocol" to "envd_connect_process_v1",
            "sandboxCommandBackendKind" to "provider_native",
            "sandboxCommandBackendResolvedKind" to "provider_native",
            "sandboxCommandProviderNative" to "true",
            "sandboxCommandSupportsStreamingLogs" to "false",
            "sandboxCommandSupportsReconnect" to "true",
            "sandboxCommandObservationMode" to "host_managed_snapshot",
            "sandboxCommandPid" to "654",
            "remoteWorkspaceRoot" to "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-retryable",
            "remoteWorkingDirectory" to "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-retryable/repo",
          ),
        ),
      ),
    )

    waitUntil {
      controller.snapshot().metadata["sandboxCommandReconnectStatus"] == "retryable_failure"
    }
    val snapshot = controller.await(100L)
    val retryAfterEpochMs =
      requireNotNull(snapshot.metadata["sandboxCommandReconnectRetryAfterEpochMs"]).toLong()

    assertEquals(ManagedProcessStatus.RUNNING, snapshot.status)
    assertEquals("retryable_failure", snapshot.metadata["sandboxCommandReconnectStatus"])
    assertEquals("retry_scheduled", snapshot.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("true", snapshot.metadata["sandboxCommandReconnectRetryable"])
    assertEquals(
      "transport_exception_before_live_attach",
      snapshot.metadata["sandboxCommandReconnectFailureStage"],
    )
    assertEquals(
      "observation_snapshot_metadata",
      snapshot.metadata["sandboxCommandReconnectSeedSource"],
    )
    assertEquals("false", snapshot.metadata["sandboxCommandReconnectProviderObservationSeedConsumed"])
    assertEquals(
      "retry_scheduled_before_live_attach",
      snapshot.metadata["sandboxCommandReconnectProviderObservationSeedState"],
    )
    assertNull(snapshot.metadata["sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs"])
    assertEquals("EOFException", snapshot.metadata["sandboxCommandReconnectFailureClass"])
    assertEquals("1", snapshot.metadata["sandboxCommandReconnectAttemptCount"])
    val lastFailureAtEpochMs =
      requireNotNull(snapshot.metadata["sandboxCommandReconnectLastFailureAtEpochMs"]).toLong()
    assertTrue(lastFailureAtEpochMs >= snapshot.updatedAtEpochMs)
    assertNull(snapshot.metadata["sandboxCommandReconnectLastAttachedAtEpochMs"])
    assertNull(snapshot.metadata["sandboxCommandReconnectLastEventAtEpochMs"])
    assertNull(snapshot.metadata["sandboxCommandReconnectLastEventKind"])
    assertTrue(retryAfterEpochMs > snapshot.updatedAtEpochMs)
    assertNull(snapshot.errorCode)
    assertNull(snapshot.finishedAtEpochMs)
    assertEquals("booting", snapshot.stdout)
  }

  @Test
  fun nativeManagedProcessReconnectTransportFailureAfterLiveAttachKeepsConsumedSeedMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-managed-reconnect-retryable-after-attach").toPath()
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-managed-reconnect-retryable-after-attach",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-retryable-after-attach",
        ),
      )
    }
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { request, onEnvelope ->
        when {
          request.url.contains("/process.Process/Connect") -> {
            onEnvelope(
              0,
              E2BEnvdProcessProtoCodec.encodeConnectResponse(
                E2BEnvdProcessEvent.Data(stdout = " live".toByteArray(StandardCharsets.UTF_8)),
              ),
            )
            throw EOFException("socket closed after attach")
          }

          else -> error("Unexpected stream request ${request.url}")
        }
      }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = RecordingPythonRuntime(),
      transport = transport,
      json = json,
    )

    val factory = backend.createManagedProcessControllerFactory() as ReconnectableManagedProcessControllerFactory
    val controller = requireNotNull(
      factory.reconnect(
        ManagedProcessSnapshot(
          processId = "proc-native-reconnect-retryable-after-attach",
          taskId = "task-native-reconnect-retryable-after-attach",
          command = "npm",
          args = listOf("run", "dev"),
          workingDirectory = workspaceRoot.resolve("repo").toString(),
          status = ManagedProcessStatus.RUNNING,
          processStarted = true,
          timeoutMs = 5_000L,
          stdout = "booting",
          startedAtEpochMs = 100L,
          updatedAtEpochMs = 100L,
          metadata = mapOf(
            "runtimeKind" to "command_exec",
            "runtimeBackend" to "e2b_envd_native_command",
            "runtimeTransport" to "connect_proto_minimal",
            "sandboxProvider" to SandboxProviderId.E2B.wireValue,
            "sandboxCommandApi" to "envd_process_start",
            "sandboxCommandNativeProtocol" to "envd_connect_process_v1",
            "sandboxCommandBackendKind" to "provider_native",
            "sandboxCommandBackendResolvedKind" to "provider_native",
            "sandboxCommandProviderNative" to "true",
            "sandboxCommandSupportsStreamingLogs" to "false",
            "sandboxCommandSupportsReconnect" to "true",
            "sandboxCommandObservationMode" to "host_managed_snapshot",
            "sandboxCommandPid" to "654",
            "remoteWorkspaceRoot" to "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-retryable-after-attach",
            "remoteWorkingDirectory" to "/home/user/opencray/workspace-sticky/sandbox-managed-reconnect-retryable-after-attach/repo",
          ),
        ),
      ),
    )

    waitUntil {
      controller.snapshot().metadata["sandboxCommandReconnectStatus"] == "retryable_failure"
    }
    val snapshot = controller.await(100L)

    assertEquals(ManagedProcessStatus.RUNNING, snapshot.status)
    assertEquals("retryable_failure", snapshot.metadata["sandboxCommandReconnectStatus"])
    assertEquals("retry_scheduled", snapshot.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals(
      "transport_exception_after_live_attach",
      snapshot.metadata["sandboxCommandReconnectFailureStage"],
    )
    assertEquals(
      "observation_snapshot_metadata",
      snapshot.metadata["sandboxCommandReconnectSeedSource"],
    )
    assertEquals("true", snapshot.metadata["sandboxCommandReconnectProviderObservationSeedConsumed"])
    assertEquals(
      "consumed_live_attach",
      snapshot.metadata["sandboxCommandReconnectProviderObservationSeedState"],
    )
    assertNotNull(snapshot.metadata["sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs"])
    assertNotNull(snapshot.metadata["sandboxCommandReconnectLastAttachedAtEpochMs"])
    assertNotNull(snapshot.metadata["sandboxCommandReconnectLastEventAtEpochMs"])
    assertEquals("data", snapshot.metadata["sandboxCommandReconnectLastEventKind"])
    assertEquals("booting live", snapshot.stdout)
  }

  @Test
  fun nativeManagedProcessFallsBackToPythonWrapperWithAttemptMetadataOnTransportFailureBeforeStart() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-managed-transport-fallback").toPath()
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-managed-transport-fallback",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-managed-transport-fallback",
        ),
      )
    }
    val pythonRuntime = RecordingPythonRuntime(
      result = ExecutionResult(
        taskId = "task-managed-transport-wrapper",
        status = ExecutionStatus.SUCCESS,
        exitCode = 0,
        stdout = encodedWrapperPayload(
          CommandWrapperResultPayload(
            exitCode = 0,
            stdout = "wrapper managed stdout after transport failure",
            stderr = "",
            processStarted = true,
          ),
        ),
        stderr = "",
        startedAtEpochMs = 100L,
        finishedAtEpochMs = 200L,
        metadata = mapOf("runtimeBackend" to "e2b_code_interpreter"),
      ),
    )
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { _, _ -> throw EOFException("socket closed before start") }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = pythonRuntime,
      transport = transport,
      json = json,
    )

    val controller = backend.createManagedProcessControllerFactory().start(
      com.opencray.runtime.process.ManagedProcessStartRequest(
        processId = "proc-managed-transport-wrapper",
        taskId = "task-managed-transport-wrapper",
        command = "git",
        args = listOf("status"),
        workingDirectory = workspaceRoot.toString(),
        timeoutMs = 5_000L,
        requestedAtEpochMs = 100L,
      ),
    )

    val snapshot = controller.await(5_000L)
    assertEquals(ManagedProcessStatus.SUCCESS, snapshot.status)
    assertEquals("wrapper managed stdout after transport failure", snapshot.stdout)
    assertEquals("python_wrapper", snapshot.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals(
      "native_command_transport_error",
      snapshot.metadata["sandboxCommandBackendFallbackReasonCode"],
    )
    assertEquals("true", snapshot.metadata["sandboxCommandNativeAttempted"])
    assertEquals("transport_exception", snapshot.metadata["sandboxCommandNativeAttemptFailureStage"])
    assertEquals("EOFException", snapshot.metadata["sandboxCommandNativeAttemptTransportFailureClass"])
    assertEquals("socket closed before start", snapshot.metadata["sandboxCommandNativeAttemptTransportFailureMessage"])
    assertEquals(
      "/home/user/opencray/workspace-sticky/sandbox-managed-transport-fallback",
      snapshot.metadata["sandboxCommandNativeAttemptRemoteWorkingDirectory"],
    )
    assertEquals(1, transport.requests.size)
    assertTrue(transport.unaryRequests.isEmpty())
    assertTrue(pythonRuntime.requests.isNotEmpty())
  }

  @Test
  fun nativeManagedProcessFallsBackToPythonWrapperWithAttemptMetadataOnHttpFailureBeforeStart() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-managed-http-fallback").toPath()
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-managed-http-fallback",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-managed-http-fallback",
        ),
      )
    }
    val pythonRuntime = RecordingPythonRuntime(
      result = ExecutionResult(
        taskId = "task-managed-http-wrapper",
        status = ExecutionStatus.SUCCESS,
        exitCode = 0,
        stdout = encodedWrapperPayload(
          CommandWrapperResultPayload(
            exitCode = 0,
            stdout = "wrapper managed stdout after http failure",
            stderr = "",
            processStarted = true,
          ),
        ),
        stderr = "",
        startedAtEpochMs = 100L,
        finishedAtEpochMs = 200L,
        metadata = mapOf("runtimeBackend" to "e2b_code_interpreter"),
      ),
    )
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { _, _ -> E2BResponse(statusCode = 503, body = "service unavailable") }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = pythonRuntime,
      transport = transport,
      json = json,
    )

    val controller = backend.createManagedProcessControllerFactory().start(
      com.opencray.runtime.process.ManagedProcessStartRequest(
        processId = "proc-managed-http-wrapper",
        taskId = "task-managed-http-wrapper",
        command = "git",
        args = listOf("status"),
        workingDirectory = workspaceRoot.toString(),
        timeoutMs = 5_000L,
        requestedAtEpochMs = 100L,
      ),
    )

    val snapshot = controller.await(5_000L)
    assertEquals(ManagedProcessStatus.SUCCESS, snapshot.status)
    assertEquals("wrapper managed stdout after http failure", snapshot.stdout)
    assertEquals("python_wrapper", snapshot.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals(
      "native_command_http_error",
      snapshot.metadata["sandboxCommandBackendFallbackReasonCode"],
    )
    assertEquals("true", snapshot.metadata["sandboxCommandNativeAttempted"])
    assertEquals("http_response_non_success", snapshot.metadata["sandboxCommandNativeAttemptFailureStage"])
    assertEquals("503", snapshot.metadata["sandboxCommandNativeAttemptHttpStatusCode"])
    assertEquals(
      "/home/user/opencray/workspace-sticky/sandbox-managed-http-fallback",
      snapshot.metadata["sandboxCommandNativeAttemptRemoteWorkingDirectory"],
    )
    assertEquals(1, transport.requests.size)
    assertTrue(transport.unaryRequests.isEmpty())
    assertTrue(pythonRuntime.requests.isNotEmpty())
  }

  @Test
  fun nativeForegroundCommandFallsBackToPythonWrapperWithHttpAttemptMetadataOnHttpFailure() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-command-http-failure").toPath()
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-http-failure",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-http-failure",
        ),
      )
    }
    val pythonRuntime = RecordingPythonRuntime(
      result = ExecutionResult(
        taskId = "task-http-wrapper",
        status = ExecutionStatus.SUCCESS,
        exitCode = 0,
        stdout = encodedWrapperPayload(
          CommandWrapperResultPayload(
            exitCode = 0,
            stdout = "wrapper stdout after http failure",
            stderr = "",
            processStarted = true,
          ),
        ),
        stderr = "",
        startedAtEpochMs = 100L,
        finishedAtEpochMs = 200L,
        metadata = mapOf("runtimeBackend" to "e2b_code_interpreter"),
      ),
    )
    val transport = FakeEnvdCommandTransport().apply {
      streamHandler = { _, _ -> E2BResponse(statusCode = 503, body = "service unavailable") }
    }
    val backend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = pythonRuntime,
      transport = transport,
      json = json,
    )

    val result = backend.createCommandExecutor().execute(
      request = CommandExecutionRequest(
        taskId = "task-http-wrapper",
        command = "git",
        args = listOf("status"),
        workingDirectory = workspaceRoot.toString(),
        requestedAtEpochMs = 100L,
      ),
      policyDecision = allowPolicy(),
      approvalToken = null,
      hooks = hooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("wrapper stdout after http failure", result.stdout)
    assertEquals("python_wrapper", result.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals(
      "native_command_http_error",
      result.metadata["sandboxCommandBackendFallbackReasonCode"],
    )
    assertEquals("true", result.metadata["sandboxCommandNativeAttempted"])
    assertEquals(
      "http_response_non_success",
      result.metadata["sandboxCommandNativeAttemptFailureStage"],
    )
    assertEquals("503", result.metadata["sandboxCommandNativeAttemptHttpStatusCode"])
    assertEquals(
      "/home/user/opencray/workspace-sticky/sandbox-http-failure",
      result.metadata["sandboxCommandNativeAttemptRemoteWorkingDirectory"],
    )
    assertEquals(1, transport.requests.size)
    assertTrue(pythonRuntime.requests.isNotEmpty())
  }

  private fun sandboxSettings(): ResolvedSandboxSettings = ResolvedSandboxSettings(
    state = SandboxSettingsState(
      enabled = true,
      providerId = SandboxProviderId.E2B.wireValue,
      defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
      e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
    ),
    e2bApiKey = "secret-token",
  )

  private fun allowPolicy(): PolicyDecision = PolicyDecision(
    outcome = PolicyDecisionOutcome.ALLOW,
    reasonCode = "TEST_ALLOW",
  )

  private fun hooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest ->
      error("Retry not expected in E2BEnvdNativeCommandExecutionTest.")
    },
  )

  private fun connectPayload(bodyBytes: ByteArray): ByteArray {
    assertTrue(bodyBytes.size >= 5)
    val length = (
      ((bodyBytes[1].toInt() and 0xFF) shl 24) or
        ((bodyBytes[2].toInt() and 0xFF) shl 16) or
        ((bodyBytes[3].toInt() and 0xFF) shl 8) or
        (bodyBytes[4].toInt() and 0xFF)
      )
    return bodyBytes.copyOfRange(5, 5 + length)
  }

  private fun encodedWrapperPayload(payload: CommandWrapperResultPayload): String {
    val encoded = Base64.getEncoder().encodeToString(
      json.encodeToString(CommandWrapperResultPayload.serializer(), payload).toByteArray(StandardCharsets.UTF_8),
    )
    return "$COMMAND_RESULT_PREFIX$encoded"
  }

  private fun waitUntil(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (predicate()) {
        return
      }
      Thread.sleep(10L)
    }
    assertTrue("Condition not satisfied within ${timeoutMs}ms.", predicate())
  }

  private class FakeEnvdCommandTransport : E2BEnvdCommandTransport {
    val requests = mutableListOf<E2BEnvdCommandTransportRequest>()
    val unaryRequests = mutableListOf<E2BEnvdCommandTransportRequest>()
    var streamHandler: (E2BEnvdCommandTransportRequest, (Int, ByteArray) -> Unit) -> E2BResponse =
      { request, _ -> error("Unexpected envd command stream ${request.method} ${request.url}") }
    var unaryHandler: (E2BEnvdCommandTransportRequest) -> E2BResponse =
      { request -> error("Unexpected envd command unary ${request.method} ${request.url}") }

    override fun stream(
      request: E2BEnvdCommandTransportRequest,
      onEnvelope: (flags: Int, payload: ByteArray) -> Unit,
    ): E2BResponse {
      requests += request
      return streamHandler(request, onEnvelope)
    }

    override fun unary(
      request: E2BEnvdCommandTransportRequest,
    ): E2BResponse {
      unaryRequests += request
      return unaryHandler(request)
    }
  }

  private class RecordingPythonRuntime(
    private val result: ExecutionResult = ExecutionResult(
      taskId = "unused",
      status = ExecutionStatus.SUCCESS,
      exitCode = 0,
      stdout = "",
      stderr = "",
      startedAtEpochMs = 100L,
      finishedAtEpochMs = 200L,
    ),
  ) : PythonScriptRuntime {
    val requests = mutableListOf<PythonExecRequest>()

    override fun exec(request: PythonExecRequest): ExecutionResult {
      requests += request
      return result.copy(taskId = request.taskId)
    }
  }
}
