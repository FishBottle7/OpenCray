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
    assertEquals("persisted", result.metadata["sandboxCommandSessionSource"])
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
    assertEquals("host_managed_snapshot", runningSnapshot.metadata["sandboxCommandObservationMode"])
    assertEquals("654", runningSnapshot.metadata["sandboxCommandPid"])
    assertEquals("booting", runningSnapshot.stdout)

    val terminateSnapshot = controller.terminate()
    waitUntil { transport.unaryRequests.size == 1 }
    assertEquals("true", terminateSnapshot.metadata["terminationRequested"])
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
    assertEquals("host_managed_snapshot", completedSnapshot.metadata["sandboxCommandObservationMode"])
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
    assertEquals(
      "seed_snapshot_then_live_attach",
      runningSnapshot.metadata["sandboxCommandReconnectResumeMode"],
    )
    assertEquals("false", runningSnapshot.metadata["sandboxCommandReconnectBackfillSupported"])
    assertEquals("true", runningSnapshot.metadata["sandboxCommandReconnectOutputGapRisk"])
    assertEquals("7", runningSnapshot.metadata["sandboxCommandReconnectSeededStdoutBytes"])
    assertEquals("0", runningSnapshot.metadata["sandboxCommandReconnectSeededStderrBytes"])
    val lastAttachedAtEpochMs =
      requireNotNull(runningSnapshot.metadata["sandboxCommandReconnectLastAttachedAtEpochMs"]).toLong()
    val lastEventAtEpochMs =
      requireNotNull(runningSnapshot.metadata["sandboxCommandReconnectLastEventAtEpochMs"]).toLong()
    assertTrue(lastAttachedAtEpochMs >= runningSnapshot.startedAtEpochMs)
    assertTrue(lastEventAtEpochMs >= lastAttachedAtEpochMs)
    assertEquals("data", runningSnapshot.metadata["sandboxCommandReconnectLastEventKind"])
    assertEquals("true", runningSnapshot.metadata["sandboxCommandSupportsReconnect"])

    allowStreamComplete.countDown()
    val completedSnapshot = controller.await(5_000L)
    assertEquals(ManagedProcessStatus.SUCCESS, completedSnapshot.status)
    assertEquals(0, completedSnapshot.exitCode)
    assertEquals("completed", completedSnapshot.metadata["sandboxCommandReconnectStatus"])
    assertEquals("completed", completedSnapshot.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("200", completedSnapshot.metadata["sandboxCommandReconnectHttpStatusCode"])
    assertEquals("true", completedSnapshot.metadata["sandboxCommandReconnectOutputGapRisk"])

    val connectRequest = transport.requests.single()
    assertTrue(connectRequest.url.contains("https://49983-sandbox-managed-reconnect.e2b.app/process.Process/Connect"))
    val decodedConnectRequest = E2BEnvdProcessProtoCodec.decodeConnectRequest(connectPayload(connectRequest.bodyBytes))
    assertEquals(654, decodedConnectRequest.process.pid)
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
    assertEquals("transport_exception_after_connect", snapshot.metadata["sandboxCommandReconnectFailureStage"])
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
