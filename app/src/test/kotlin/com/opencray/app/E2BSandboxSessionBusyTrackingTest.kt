package com.opencray.app

import com.opencray.app.e2b.E2BEnvdProcessEvent
import com.opencray.app.e2b.E2BEnvdProcessProtoCodec
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.PythonExecRequest
import com.opencray.runtime.PythonScriptRuntime
import com.opencray.runtime.SandboxSessionCloseOutcome
import com.opencray.runtime.SandboxSessionInfoRequest
import com.opencray.runtime.SandboxSessionLifecycleStatus
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class E2BSandboxSessionBusyTrackingTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  @After
  fun tearDown() {
    SharedE2BSandboxActivityTracker.clearForTest()
  }

  @Test
  fun nativeManagedProcessIsReportedBusyBySessionInfoAndSessionClose() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-native-session-busy").toPath()
    Files.createDirectories(workspaceRoot.resolve("repo"))
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-native-busy",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 100L,
          remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sb-native-busy",
        ),
      )
    }
    val deleteSandboxCount = AtomicInteger(0)
    val runtime = E2BCodeInterpreterPythonRuntime(
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      transport = object : E2BTransport {
        override fun request(request: E2BRequest): E2BResponse = when {
          request.method == "DELETE" && request.url == "https://api.e2b.app/sandboxes/sb-native-busy" -> {
            deleteSandboxCount.incrementAndGet()
            E2BResponse(statusCode = 204)
          }

          else -> error("Unexpected request ${request.method} ${request.url}")
        }

        override fun upload(request: E2BUploadRequest): E2BResponse =
          error("Unexpected upload ${request.url}")

        override fun download(request: E2BDownloadRequest): E2BBinaryResponse =
          error("Unexpected download ${request.url}")

        override fun stream(
          request: E2BRequest,
          onLine: (String) -> Unit,
        ): E2BResponse = error("Unexpected stream ${request.method} ${request.url}")
      },
    )
    val streamStarted = CountDownLatch(1)
    val allowStreamComplete = CountDownLatch(1)
    val envdTransport = FakeEnvdCommandTransport().apply {
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
      pythonRuntime = NoopPythonRuntime(),
      transport = envdTransport,
      json = json,
    )

    val controller = backend.createManagedProcessControllerFactory().start(
      ManagedProcessStartRequest(
        processId = "proc-native-busy",
        taskId = "task-native-busy",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = workspaceRoot.resolve("repo").toString(),
        timeoutMs = 5_000L,
        requestedAtEpochMs = 100L,
      ),
    )

    assertTrue(streamStarted.await(5, TimeUnit.SECONDS))
    waitUntil {
      controller.snapshot().metadata["sandboxCommandPid"] == "654"
    }

    val service = E2BSandboxSessionInfoService(
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      runningRequestIdsProvider = runtime::runningRequestIdsForSandbox,
      sessionCloser = runtime::closeReusableSession,
    )

    val infoResult = service.inspect(
      SandboxSessionInfoRequest(
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(listOf("proc-native-busy"), infoResult.runningRequestIds)
    assertEquals(ManagedProcessStatus.RUNNING, controller.snapshot().status)

    val busyCloseResult = runtime.closeReusableSession(requireNotNull(sessionStore.load()))

    assertEquals(SandboxSessionCloseOutcome.BUSY, busyCloseResult.outcome)
    assertEquals("proc-native-busy", busyCloseResult.blockingRequestId)
    assertEquals(0, deleteSandboxCount.get())

    allowStreamComplete.countDown()
    val completedSnapshot = controller.await(5_000L)
    assertEquals(ManagedProcessStatus.SUCCESS, completedSnapshot.status)
    assertTrue(runtime.runningRequestIdsForSandbox("sb-native-busy").isEmpty())

    val closeResult = runtime.closeReusableSession(requireNotNull(sessionStore.load()))

    assertEquals(SandboxSessionCloseOutcome.TERMINATED, closeResult.outcome)
    assertEquals(1, deleteSandboxCount.get())
  }

  @Test
  fun durableNativeRunningProcessPreventsStaleReclaimAfterRestart() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-durable-session-busy").toPath()
    val runtimeRootDirectory = temporaryFolder.newFolder("e2b-durable-runtime-root")
    val sessionDirectory = Files.createDirectory(runtimeRootDirectory.toPath().resolve("session-durable"))
    val durableSnapshot = ManagedProcessSnapshot(
      processId = "proc-native-durable",
      taskId = "task-native-durable",
      command = "npm",
      args = listOf("run", "dev"),
      workingDirectory = workspaceRoot.toString(),
      status = ManagedProcessStatus.RUNNING,
      processStarted = true,
      timeoutMs = 120_000L,
      startedAtEpochMs = 1_000L,
      updatedAtEpochMs = 2_000L,
      metadata = mapOf(
        "runtimeBackend" to "e2b_envd_native_command",
        "sandboxProvider" to SandboxProviderId.E2B.wireValue,
        "sandboxId" to "sb-durable-busy",
        "sandboxDomain" to "e2b.app",
        "sandboxCommandBackendResolvedKind" to "provider_native",
        "sandboxCommandNativeProtocol" to "envd_connect_process_v1",
      ),
    )
    val registryPayload = buildJsonObject {
      put("schemaVersion", 1)
      put("recordVersion", 1)
      put("updatedAtEpochMs", 2_000L)
      put(
        "snapshots",
        json.encodeToJsonElement(
          serializer = kotlinx.serialization.builtins.ListSerializer(ManagedProcessSnapshot.serializer()),
          value = listOf(durableSnapshot),
        ),
      )
    }
    Files.write(
      sessionDirectory.resolve("managed-process-registry.json"),
      registryPayload.toString().toByteArray(StandardCharsets.UTF_8),
    )
    val sessionStore = E2BSandboxSessionStore(
      keyValueStore = InMemoryE2BSandboxSessionKeyValueStore(),
    ).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-durable-busy",
          sandboxDomain = "e2b.app",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 0L,
        ),
      )
    }
    val deleteSandboxCount = AtomicInteger(0)
    val runtime = E2BCodeInterpreterPythonRuntime(
      settingsProvider = {
        sandboxSettings(
          sessionMode = SandboxSessionMode.STICKY.wireValue,
          autoResume = false,
          idleTimeoutMinutes = 1,
          timeoutAction = SandboxTimeoutAction.KILL.wireValue,
        )
      },
      sessionStore = sessionStore,
      transport = object : E2BTransport {
        override fun request(request: E2BRequest): E2BResponse = when {
          request.method == "DELETE" && request.url == "https://api.e2b.app/sandboxes/sb-durable-busy" -> {
            deleteSandboxCount.incrementAndGet()
            E2BResponse(statusCode = 204)
          }

          else -> error("Unexpected request ${request.method} ${request.url}")
        }

        override fun upload(request: E2BUploadRequest): E2BResponse =
          error("Unexpected upload ${request.url}")

        override fun download(request: E2BDownloadRequest): E2BBinaryResponse =
          error("Unexpected download ${request.url}")

        override fun stream(
          request: E2BRequest,
          onLine: (String) -> Unit,
        ): E2BResponse = error("Unexpected stream ${request.method} ${request.url}")
      },
      durableRunningRequestIdsProvider = durableE2BNativeRunningRequestIdsProvider(
        runtimeRootDirectory = runtimeRootDirectory,
      ),
    )
    val service = E2BSandboxSessionInfoService(
      settingsProvider = {
        sandboxSettings(
          sessionMode = SandboxSessionMode.STICKY.wireValue,
          autoResume = false,
          idleTimeoutMinutes = 1,
          timeoutAction = SandboxTimeoutAction.KILL.wireValue,
        )
      },
      sessionStore = sessionStore,
      runningRequestIdsProvider = runtime::runningRequestIdsForSandbox,
      sessionCloser = runtime::closeReusableSession,
      clock = { 181_000L },
    )

    val infoResult = service.inspect(
      SandboxSessionInfoRequest(
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(listOf("proc-native-durable"), infoResult.runningRequestIds)
    assertEquals(SandboxSessionLifecycleStatus.ACTIVE, infoResult.lifecycleStatus)
    assertTrue(!infoResult.sessionIsStale)
    assertEquals(0, deleteSandboxCount.get())

    val closeResult = runtime.closeReusableSession(requireNotNull(sessionStore.load()))

    assertEquals(SandboxSessionCloseOutcome.BUSY, closeResult.outcome)
    assertEquals("proc-native-durable", closeResult.blockingRequestId)
    assertEquals(0, deleteSandboxCount.get())
  }

  @Test
  fun durableNativeRunningRequestIdsProviderUsesRegistryProjectionNormalization() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-durable-registry-normalized").toPath()
    val runtimeRootDirectory = temporaryFolder.newFolder("e2b-durable-normalized-runtime-root")
    val sessionDirectory = Files.createDirectory(runtimeRootDirectory.toPath().resolve("session-normalized"))
    val staleRunningSnapshot = ManagedProcessSnapshot(
      processId = "proc-native-normalized",
      taskId = "task-native-normalized",
      command = "npm",
      args = listOf("run", "dev"),
      workingDirectory = workspaceRoot.toString(),
      status = ManagedProcessStatus.RUNNING,
      processStarted = true,
      timeoutMs = 120_000L,
      startedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_500L,
      metadata = mapOf(
        "runtimeBackend" to "e2b_envd_native_command",
        "sandboxProvider" to SandboxProviderId.E2B.wireValue,
        "sandboxId" to "sb-normalized",
        "sandboxCommandBackendResolvedKind" to "provider_native",
        "sandboxCommandNativeProtocol" to "envd_connect_process_v1",
      ),
    )
    val newerTerminalSnapshot = staleRunningSnapshot.copy(
      status = ManagedProcessStatus.SUCCESS,
      exitCode = 0,
      startedAtEpochMs = 2_000L,
      updatedAtEpochMs = 2_500L,
      finishedAtEpochMs = 2_500L,
    )
    val registryPayload = buildJsonObject {
      put("schemaVersion", 1)
      put("recordVersion", 1)
      put("updatedAtEpochMs", 2_500L)
      put(
        "snapshots",
        json.encodeToJsonElement(
          serializer = kotlinx.serialization.builtins.ListSerializer(ManagedProcessSnapshot.serializer()),
          value = listOf(staleRunningSnapshot, newerTerminalSnapshot),
        ),
      )
    }
    Files.write(
      sessionDirectory.resolve("managed-process-registry.json"),
      registryPayload.toString().toByteArray(StandardCharsets.UTF_8),
    )

    val runningRequestIds = durableE2BNativeRunningRequestIdsProvider(
      runtimeRootDirectory = runtimeRootDirectory,
    )("sb-normalized")

    assertEquals(emptyList<String>(), runningRequestIds)
  }

  private fun sandboxSettings(): ResolvedSandboxSettings = ResolvedSandboxSettings(
    state = SandboxSettingsState(
      enabled = true,
      providerId = SandboxProviderId.E2B.wireValue,
      defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
      sessionMode = SandboxSessionMode.STICKY.wireValue,
      autoResume = true,
      e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
    ),
    e2bApiKey = "secret-token",
  )

  private fun sandboxSettings(
    sessionMode: String,
    autoResume: Boolean,
    idleTimeoutMinutes: Int,
    timeoutAction: String,
  ): ResolvedSandboxSettings = ResolvedSandboxSettings(
    state = SandboxSettingsState(
      enabled = true,
      providerId = SandboxProviderId.E2B.wireValue,
      defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
      sessionMode = sessionMode,
      autoResume = autoResume,
      idleTimeoutMinutes = idleTimeoutMinutes,
      timeoutAction = timeoutAction,
      e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
    ),
    e2bApiKey = "secret-token",
  )

  private fun waitUntil(
    timeoutMs: Long = 5_000L,
    predicate: () -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (predicate()) {
        return
      }
      Thread.sleep(10L)
    }
    error("Condition was not satisfied within ${timeoutMs}ms.")
  }

  private class NoopPythonRuntime : PythonScriptRuntime {
    override fun exec(request: PythonExecRequest): ExecutionResult = ExecutionResult(
      taskId = request.taskId,
      status = ExecutionStatus.FAILED,
      errorCode = "UNEXPECTED_PYTHON_EXEC",
      errorMessage = "Python runtime should not be used in this test.",
      startedAtEpochMs = 0L,
      finishedAtEpochMs = 0L,
    )
  }

  private class FakeEnvdCommandTransport : E2BEnvdCommandTransport {
    var streamHandler: (E2BEnvdCommandTransportRequest, (Int, ByteArray) -> Unit) -> E2BResponse =
      { request, _ -> error("Unexpected stream ${request.method} ${request.url}") }

    override fun stream(
      request: E2BEnvdCommandTransportRequest,
      onEnvelope: (flags: Int, payload: ByteArray) -> Unit,
    ): E2BResponse = streamHandler(request, onEnvelope)
  }
}
