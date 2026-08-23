package com.opencray.app

import com.opencray.app.e2b.E2BEnvdProcessEvent
import com.opencray.app.e2b.E2BEnvdProcessProtoCodec
import com.opencray.runtime.process.FileBackedAgentProcessRegistry
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_CURRENT_DURABLE_RUNTIME_CONTROLLER_ID_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY
import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessObservationState
import com.opencray.runtime.process.ManagedProcessRemoteHandle
import com.opencray.runtime.process.ManagedProcessRestoreDecision
import com.opencray.runtime.process.ManagedProcessRestoreMode
import com.opencray.runtime.process.ManagedProcessRestoreScope
import com.opencray.runtime.process.ManagedProcessRuntimeIdentity
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class E2BManagedProcessRegistryReconnectIntegrationTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun rebuiltOwnerReconnectsPersistedE2BProcessThroughProviderRoute() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-registry-reconnect-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("repo"))
    val registryDirectory = temporaryFolder.newFolder("e2b-registry-reconnect-state")
    val remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sandbox-registry-reconnect"
    val processId = "proc-registry-reconnect"
    val sourceIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "runtime-process-before-death",
      runtimeControllerId = "runtime-controller-before-death",
      durableRuntimeControllerId = "durable-detached-controller",
    )
    val rebuiltIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "runtime-process-after-repair",
      runtimeControllerId = "runtime-controller-after-repair",
      durableRuntimeControllerId = "durable-detached-controller",
    )
    val sourceRegistry = FileBackedAgentProcessRegistry(
      directory = registryDirectory,
      controllerFactory = persistedE2BProcessFactory(
        remoteWorkspaceRoot = remoteWorkspaceRoot,
      ),
      runtimeIdentity = sourceIdentity,
    )
    sourceRegistry.start(
      ManagedProcessStartRequest(
        processId = processId,
        taskId = "task-registry-reconnect",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = workspaceRoot.resolve("repo").toString(),
        timeoutMs = 30_000L,
        requestedAtEpochMs = 100L,
        ownerIdentity = sourceIdentity,
        metadata = mapOf(
          "executionBackend" to ResolvedExecutionBackend.SANDBOX_REMOTE.wireValue,
          "runtimeKind" to "command_exec",
          "runtimeBackend" to "e2b_envd_native_command",
          "runtimeTransport" to "connect_proto_minimal",
          "sandboxProvider" to SandboxProviderId.E2B.wireValue,
          "sandboxCommandBackendKind" to "provider_native",
          "sandboxCommandBackendResolvedKind" to "provider_native",
          "sandboxCommandSupportsReconnect" to "true",
          "sandboxCommandNativeProtocol" to "envd_connect_process_v1",
          "sandboxCommandPid" to "654",
          "remoteWorkspaceRoot" to remoteWorkspaceRoot,
          "remoteWorkingDirectory" to "$remoteWorkspaceRoot/repo",
        ),
      ),
    )

    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sandbox-registry-reconnect",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 200L,
          remoteWorkspaceRoot = remoteWorkspaceRoot,
        ),
      )
    }
    val connectStarted = CountDownLatch(1)
    val allowConnectComplete = CountDownLatch(1)
    val transport = RecordingEnvdCommandTransport { request, onEnvelope ->
      check(request.url.contains("/process.Process/Connect")) {
        "Expected provider reconnect request, got ${request.method} ${request.url}."
      }
      connectStarted.countDown()
      onEnvelope(
        0,
        E2BEnvdProcessProtoCodec.encodeConnectResponse(
          E2BEnvdProcessEvent.Start(pid = 654),
        ),
      )
      onEnvelope(
        0,
        E2BEnvdProcessProtoCodec.encodeConnectResponse(
          E2BEnvdProcessEvent.Data(
            stdout = " after reconnect".toByteArray(StandardCharsets.UTF_8),
          ),
        ),
      )
      check(allowConnectComplete.await(5, TimeUnit.SECONDS)) {
        "Timed out waiting to complete the provider reconnect stream."
      }
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
    val providerBackend = E2BMinimalProtocolSandboxCommandExecutionBackend(
      workspaceRootProvider = { workspaceRoot },
      settingsProvider = ::sandboxSettings,
      sessionStore = sessionStore,
      activeSessionProvider = { null },
      pythonRuntime = UnexpectedPythonRuntime(),
      transport = transport,
      json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    )
    val unexpectedFactory = ManagedProcessControllerFactory {
      error("Persisted E2B reconnect must not use a local or Python managed-process factory.")
    }
    val routingFactory = RoutingManagedProcessControllerFactory(
      settingsProvider = ::sandboxSettings,
      pythonRuntimeFactory = unexpectedFactory,
      localFactory = unexpectedFactory,
      sandboxFactoryProvider = { settings ->
        when (SandboxProviderId.fromWireValue(settings.state.providerId)) {
          SandboxProviderId.E2B -> providerBackend.createManagedProcessControllerFactory()
          null -> null
        }
      },
    )

    try {
      val rebuiltRegistry = FileBackedAgentProcessRegistry(
        directory = registryDirectory,
        controllerFactory = routingFactory,
        runtimeIdentity = rebuiltIdentity,
      )

      assertTrue(connectStarted.await(5, TimeUnit.SECONDS))
      waitUntil {
        rebuiltRegistry.read(processId)
          ?.metadata
          ?.get("sandboxCommandReconnectStatus") == "attached"
      }
      val attached = requireNotNull(rebuiltRegistry.read(processId))

      assertEquals(ManagedProcessStatus.RUNNING, attached.status)
      assertEquals("booting after reconnect", attached.stdout)
      assertEquals("attached_live", attached.metadata["sandboxCommandReconnectRecoveryState"])
      assertEquals(
        ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
        attached.metadata[MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY],
      )
      assertEquals(
        ManagedProcessRestoreDecision.RECONNECT_ATTEMPTED.wireValue,
        attached.metadata[MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY],
      )
      assertEquals(
        rebuiltIdentity.processStartId,
        attached.metadata[MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY],
      )
      assertEquals(
        rebuiltIdentity.runtimeControllerId,
        attached.metadata[MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY],
      )
      assertEquals(
        rebuiltIdentity.durableRuntimeControllerId,
        attached.metadata[MANAGED_PROCESS_RESTORE_CURRENT_DURABLE_RUNTIME_CONTROLLER_ID_METADATA_KEY],
      )
      assertEquals(SandboxProviderId.E2B.wireValue, attached.remoteHandle?.provider)
      assertEquals("654", attached.remoteHandle?.liveSelectorValue)

      val connectRequest = transport.requests.single()
      val decodedRequest = E2BEnvdProcessProtoCodec.decodeConnectRequest(
        grpcPayload(connectRequest.bodyBytes),
      )
      assertEquals(654, decodedRequest.process.pid)

      allowConnectComplete.countDown()
      val completed = requireNotNull(rebuiltRegistry.wait(processId, 5_000L))
      assertEquals(ManagedProcessStatus.SUCCESS, completed.status)
      assertEquals("completed", completed.metadata["sandboxCommandReconnectRecoveryState"])

      val persisted = FileBackedAgentProcessRegistry(
        directory = registryDirectory,
        runtimeIdentity = rebuiltIdentity,
        restoreMode = ManagedProcessRestoreMode.PROJECTION_ONLY,
      ).read(processId)
      assertNotNull(persisted)
      assertEquals(ManagedProcessStatus.SUCCESS, persisted?.status)
      assertEquals("completed", persisted?.metadata?.get("sandboxCommandReconnectRecoveryState"))
    } finally {
      allowConnectComplete.countDown()
    }
  }

  private fun persistedE2BProcessFactory(
    remoteWorkspaceRoot: String,
  ): ManagedProcessControllerFactory = ManagedProcessControllerFactory { request ->
    SnapshotManagedProcessController(
      ManagedProcessSnapshot(
        processId = request.processId,
        taskId = request.taskId,
        command = request.command,
        args = request.args,
        workingDirectory = request.workingDirectory,
        status = ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = request.timeoutMs,
        stdout = "booting",
        startedAtEpochMs = request.requestedAtEpochMs,
        updatedAtEpochMs = request.requestedAtEpochMs,
        remoteHandle = ManagedProcessRemoteHandle(
          provider = SandboxProviderId.E2B.wireValue,
          sandboxId = "sandbox-registry-reconnect",
          sandboxDomain = "e2b.app",
          commandIdKind = "tag",
          commandId = request.processId,
          providerHandleKind = "envd_process",
          stableSelectorKind = "tag",
          stableSelectorValue = request.processId,
          liveSelectorKind = "pid",
          liveSelectorValue = "654",
          remoteWorkspaceRoot = remoteWorkspaceRoot,
          remoteWorkingDirectory = "$remoteWorkspaceRoot/repo",
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
        ownerIdentity = request.ownerIdentity,
        metadata = request.metadata,
      ),
    )
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

  private fun grpcPayload(bodyBytes: ByteArray): ByteArray {
    require(bodyBytes.size >= 5)
    val length =
      ((bodyBytes[1].toInt() and 0xFF) shl 24) or
        ((bodyBytes[2].toInt() and 0xFF) shl 16) or
        ((bodyBytes[3].toInt() and 0xFF) shl 8) or
        (bodyBytes[4].toInt() and 0xFF)
    return bodyBytes.copyOfRange(5, 5 + length)
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

  private class SnapshotManagedProcessController(
    private val snapshot: ManagedProcessSnapshot,
  ) : ManagedProcessController {
    override fun snapshot(): ManagedProcessSnapshot = snapshot

    override fun await(timeoutMs: Long): ManagedProcessSnapshot = snapshot

    override fun terminate(): ManagedProcessSnapshot = snapshot
  }

  private class UnexpectedPythonRuntime : com.opencray.runtime.PythonScriptRuntime {
    override fun exec(
      request: com.opencray.runtime.PythonExecRequest,
    ): com.opencray.core.contracts.ExecutionResult = error(
      "Persisted E2B reconnect must not execute through the Python wrapper.",
    )
  }

  private class RecordingEnvdCommandTransport(
    private val streamHandler: (
      E2BEnvdCommandTransportRequest,
      (Int, ByteArray) -> Unit,
    ) -> E2BResponse,
  ) : E2BEnvdCommandTransport {
    val requests = mutableListOf<E2BEnvdCommandTransportRequest>()

    override fun stream(
      request: E2BEnvdCommandTransportRequest,
      onEnvelope: (flags: Int, payload: ByteArray) -> Unit,
    ): E2BResponse {
      requests += request
      return streamHandler(request, onEnvelope)
    }

    override fun unary(request: E2BEnvdCommandTransportRequest): E2BResponse =
      error("Unexpected envd command unary ${request.method} ${request.url}")
  }
}
