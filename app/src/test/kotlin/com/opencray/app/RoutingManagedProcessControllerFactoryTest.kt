package com.opencray.app

import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
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
  ) : ManagedProcessControllerFactory {
    val requests = mutableListOf<ManagedProcessStartRequest>()

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
  }
}
