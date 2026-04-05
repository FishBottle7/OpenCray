package com.opencray.app

import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.process.ReconnectableManagedProcessControllerFactory
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingManagedProcessControllerFactoryTest {
  @Test
  fun localPreferenceUsesLocalFactoryWithoutResolvingSandboxFactory() {
    val localFactory = RecordingManagedProcessControllerFactory("local")
    val sandboxFactoryCalls = AtomicInteger(0)
    val factory = RoutingManagedProcessControllerFactory(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            defaultBackend = SandboxExecutionBackendPreference.LOCAL.wireValue,
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      pythonRuntimeFactory = RecordingManagedProcessControllerFactory("python"),
      localFactory = localFactory,
      sandboxFactoryProvider = {
        sandboxFactoryCalls.incrementAndGet()
        RecordingManagedProcessControllerFactory("sandbox")
      },
    )

    val snapshot = factory.start(commandRequest()).snapshot()

    assertEquals(1, localFactory.requests.size)
    assertEquals(0, sandboxFactoryCalls.get())
    assertEquals("local", snapshot.metadata["factoryBackend"])
    assertEquals("local_host", snapshot.metadata["executionBackend"])
  }

  @Test
  fun sandboxPreferenceUsesSandboxFactoryWithoutTouchingLocalFactory() {
    val localFactory = RecordingManagedProcessControllerFactory("local")
    val sandboxFactory = RecordingManagedProcessControllerFactory("sandbox")
    val factory = RoutingManagedProcessControllerFactory(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      pythonRuntimeFactory = RecordingManagedProcessControllerFactory("python"),
      localFactory = localFactory,
      sandboxFactoryProvider = { sandboxFactory },
    )

    val snapshot = factory.start(commandRequest()).snapshot()

    assertTrue(localFactory.requests.isEmpty())
    assertEquals(1, sandboxFactory.requests.size)
    assertEquals("sandbox", snapshot.metadata["factoryBackend"])
    assertEquals("sandbox_remote", snapshot.metadata["executionBackend"])
    assertEquals("managed_process_start", snapshot.metadata["sandboxTraceRouteKind"])
    assertEquals("sandbox_remote", snapshot.metadata["sandboxTraceExecutionBackend"])
    assertTrue(!snapshot.metadata["sandboxTraceId"].isNullOrBlank())
    assertTrue(!snapshot.metadata["sandboxTraceRouteSpanId"].isNullOrBlank())
    assertTrue(!snapshot.metadata["sandboxTraceExecutionSpanId"].isNullOrBlank())
    assertEquals(
      snapshot.metadata["sandboxTraceRouteSpanId"],
      snapshot.metadata["sandboxTraceExecutionParentSpanId"],
    )
  }

  @Test
  fun managedPythonRequestsStillGoThroughPythonRuntimeFactory() {
    val pythonFactory = RecordingManagedProcessControllerFactory("python")
    val factory = RoutingManagedProcessControllerFactory(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      pythonRuntimeFactory = pythonFactory,
      localFactory = RecordingManagedProcessControllerFactory("local"),
      sandboxFactoryProvider = { RecordingManagedProcessControllerFactory("sandbox") },
    )

    val snapshot = factory.start(
      commandRequest(
        metadata = mapOf(
          "managedByPythonRuntime" to "true",
          "runtimeKind" to "python_exec",
        ),
      ),
    ).snapshot()

    assertEquals(1, pythonFactory.requests.size)
    assertEquals("python", snapshot.metadata["factoryBackend"])
  }

  @Test
  fun reconnectUsesSandboxFactoryForPersistedSandboxSnapshotEvenWhenCurrentPreferenceIsLocal() {
    val localFactory = RecordingManagedProcessControllerFactory("local")
    val sandboxFactory = RecordingManagedProcessControllerFactory("sandbox")
    val factory = RoutingManagedProcessControllerFactory(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            defaultBackend = SandboxExecutionBackendPreference.LOCAL.wireValue,
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      pythonRuntimeFactory = RecordingManagedProcessControllerFactory("python"),
      localFactory = localFactory,
      sandboxFactoryProvider = { sandboxFactory },
    )

    val controller = factory.reconnect(
      ManagedProcessSnapshot(
        processId = "proc-sandbox",
        taskId = "task-sandbox",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 5_000L,
        startedAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        metadata = mapOf(
          "executionBackend" to ResolvedExecutionBackend.SANDBOX_REMOTE.wireValue,
          "sandboxProvider" to SandboxProviderId.E2B.wireValue,
        ),
      ),
    )

    assertNotNull(controller)
    assertTrue(localFactory.reconnectSnapshots.isEmpty())
    assertEquals(1, sandboxFactory.reconnectSnapshots.size)
    val reconnectedSnapshot = controller!!.snapshot()
    assertEquals("sandbox", reconnectedSnapshot.metadata["factoryBackend"])
    assertTrue(!reconnectedSnapshot.metadata["sandboxTraceId"].isNullOrBlank())
    assertTrue(!reconnectedSnapshot.metadata["sandboxTraceReconnectSpanId"].isNullOrBlank())
  }

  private fun commandRequest(
    metadata: Map<String, String> = emptyMap(),
  ): ManagedProcessStartRequest = ManagedProcessStartRequest(
    processId = "proc-1",
    taskId = "task-1",
    command = "npm",
    args = listOf("run", "dev"),
    workingDirectory = ".",
    timeoutMs = 5_000L,
    requestedAtEpochMs = 1_000L,
    metadata = metadata,
  )

  private class RecordingManagedProcessControllerFactory(
    private val backend: String,
  ) : ReconnectableManagedProcessControllerFactory {
    val requests = mutableListOf<ManagedProcessStartRequest>()
    val reconnectSnapshots = mutableListOf<ManagedProcessSnapshot>()

    override fun start(request: ManagedProcessStartRequest): ManagedProcessController {
      requests += request
      return object : ManagedProcessController {
        override fun snapshot(): ManagedProcessSnapshot = ManagedProcessSnapshot(
          processId = request.processId,
          taskId = request.taskId,
          command = request.command,
          args = request.args,
          workingDirectory = request.workingDirectory,
          status = ManagedProcessStatus.RUNNING,
          processStarted = true,
          timeoutMs = request.timeoutMs,
          startedAtEpochMs = 100L,
          updatedAtEpochMs = 100L,
          metadata = request.metadata + mapOf("factoryBackend" to backend),
        )

        override fun await(timeoutMs: Long): ManagedProcessSnapshot = snapshot()

        override fun terminate(): ManagedProcessSnapshot = snapshot()
      }
    }

    override fun reconnect(snapshot: ManagedProcessSnapshot): ManagedProcessController {
      reconnectSnapshots += snapshot
      return object : ManagedProcessController {
        override fun snapshot(): ManagedProcessSnapshot = snapshot.copy(
          metadata = snapshot.metadata + mapOf("factoryBackend" to backend),
        )

        override fun await(timeoutMs: Long): ManagedProcessSnapshot = snapshot()

        override fun terminate(): ManagedProcessSnapshot = snapshot()
      }
    }
  }
}
