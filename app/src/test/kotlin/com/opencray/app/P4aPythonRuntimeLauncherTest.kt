package com.opencray.app

import android.content.Context
import android.content.ContextWrapper
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class P4aPythonRuntimeLauncherTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun serviceBackedLauncherReturnsUnavailableMetadataWhenServiceIsMissing() {
    val runtimeRoot = temporaryFolder.newFolder("p4a-runtime-missing").toPath()
    val launchRequest = launchRequest(runtimeRoot = runtimeRoot, requestId = "request-missing")
    var capturedSpec: P4aPythonRuntimeServiceStartSpec? = null
    val launcher = ServiceBackedP4aPythonRuntimeLauncher(
      packageName = "org.opencray.app",
      serviceStarter = object : P4aPythonRuntimeServiceStarter {
        override fun start(spec: P4aPythonRuntimeServiceStartSpec): P4aPythonRuntimeServiceStartResult {
          capturedSpec = spec
          return P4aPythonRuntimeServiceStartResult.Unavailable(
            reason = "service_unresolved",
            message = "missing service",
            metadata = mapOf("detail" to "not-registered"),
          )
        }
      },
    )

    val result = launcher.launch(launchRequest)

    assertTrue(result is P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Unavailable)
    val unavailable = result as P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Unavailable
    assertEquals(P4aPythonRuntime.ERROR_P4A_RUNTIME_UNAVAILABLE, unavailable.errorCode)
    assertEquals("missing service", unavailable.errorMessage)
    assertEquals("service_unresolved", unavailable.metadata["launcherState"])
    assertEquals("opencraypython", unavailable.metadata["launcherServiceId"])
    assertEquals("org.opencray.app.ServiceOpencraypython", unavailable.metadata["launcherServiceClass"])
    assertEquals("not-registered", unavailable.metadata["detail"])

    val spec = checkNotNull(capturedSpec)
    assertEquals("org.opencray.app", spec.packageName)
    assertEquals("opencraypython", spec.serviceId)
    assertEquals("org.opencray.app.ServiceOpencraypython", spec.generatedServiceClassName)
    assertTrue(spec.serviceArgument.contains(""""runtimeRoot":"${runtimeRoot.toString().replace("\\", "\\\\")}""""))
    assertTrue(spec.serviceArgument.contains(""""requestId":"request-missing""""))
    assertTrue(
      spec.serviceArgument.contains(
        """"requestPath":"${runtimeRoot.resolve("requests/request-missing.json").toString().replace("\\", "\\\\")}"""",
      ),
    )
    assertTrue(
      spec.serviceArgument.contains(
        """"resultPath":"${runtimeRoot.resolve("results/request-missing.json").toString().replace("\\", "\\\\")}"""",
      ),
    )
    assertTrue(spec.serviceArgument.contains(""""pollIntervalMs":25"""))
    assertTrue(spec.serviceArgument.contains(""""once":true"""))
    assertEquals(
      "org.opencray.app.ServiceOpencraypython",
      P4aPythonRuntimeServiceContract.generatedServiceClassName("org.opencray.app"),
    )
  }

  @Test
  fun serviceBackedLauncherReturnsDispatchMetadataWhenServiceStarts() {
    val runtimeRoot = temporaryFolder.newFolder("p4a-runtime-started").toPath()
    val launchRequest = launchRequest(runtimeRoot = runtimeRoot, requestId = "request-started")
    val launcher = ServiceBackedP4aPythonRuntimeLauncher(
      packageName = "org.opencray.app",
      serviceStarter = object : P4aPythonRuntimeServiceStarter {
        override fun start(spec: P4aPythonRuntimeServiceStartSpec): P4aPythonRuntimeServiceStartResult =
          P4aPythonRuntimeServiceStartResult.Started(
            metadata = mapOf(
              "launcherResolvedServiceClass" to "org.kivy.android.PythonService",
              "launcherComponent" to "org.opencray.app/.PythonService",
            ),
          )
      },
    )

    val result = launcher.launch(launchRequest)

    assertTrue(result is P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Dispatched)
    val dispatched = result as P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Dispatched
    assertEquals("service_started", dispatched.metadata["launcherState"])
    assertEquals("opencraypython", dispatched.metadata["launcherServiceId"])
    assertEquals("org.opencray.app.ServiceOpencraypython", dispatched.metadata["launcherServiceClass"])
    assertEquals("org.kivy.android.PythonService", dispatched.metadata["launcherResolvedServiceClass"])
    assertEquals("org.opencray.app/.PythonService", dispatched.metadata["launcherComponent"])
  }

  @Test
  fun androidServiceStarterPreparesGeneratedServiceBeforeStart() {
    FakeGeneratedService.reset()
    val starter = AndroidP4aPythonRuntimeServiceStarter(
      context = ContextWrapper(null),
      classLoader = checkNotNull(FakeGeneratedService::class.java.classLoader),
    )

    val result = starter.start(
      P4aPythonRuntimeServiceStartSpec(
        packageName = "org.opencray.app",
        serviceId = "opencraypython",
        generatedServiceClassName = FakeGeneratedService::class.java.name,
        serviceArgument = """{"runtimeRoot":"/tmp/opencray-python"}""",
      ),
    )

    assertTrue(result is P4aPythonRuntimeServiceStartResult.Started)
    val started = result as P4aPythonRuntimeServiceStartResult.Started
    assertEquals("prepared", started.metadata["launcherPrepareState"])
    assertEquals("generated_static_start", started.metadata["launcherStartMode"])
    assertEquals(
      listOf("prepare", "start"),
      FakeGeneratedService.invocations,
    )
    assertEquals("""{"runtimeRoot":"/tmp/opencray-python"}""", FakeGeneratedService.capturedServiceArgument)
  }

  @Test
  fun androidServiceStarterReturnsUnavailableWhenPrepareFails() {
    FakeGeneratedService.reset()
    FakeGeneratedService.prepareShouldThrow = true
    val starter = AndroidP4aPythonRuntimeServiceStarter(
      context = ContextWrapper(null),
      classLoader = checkNotNull(FakeGeneratedService::class.java.classLoader),
    )

    val result = starter.start(
      P4aPythonRuntimeServiceStartSpec(
        packageName = "org.opencray.app",
        serviceId = "opencraypython",
        generatedServiceClassName = FakeGeneratedService::class.java.name,
        serviceArgument = """{"runtimeRoot":"/tmp/opencray-python"}""",
      ),
    )

    assertTrue(result is P4aPythonRuntimeServiceStartResult.Unavailable)
    val unavailable = result as P4aPythonRuntimeServiceStartResult.Unavailable
    assertEquals("service_prepare_failed", unavailable.reason)
    assertEquals("failed", unavailable.metadata["launcherPrepareState"])
    assertEquals(listOf("prepare"), FakeGeneratedService.invocations)
  }

  @Test
  fun androidServiceStarterRequestsGeneratedServiceStop() {
    FakeGeneratedService.reset()
    val starter = AndroidP4aPythonRuntimeServiceStarter(
      context = ContextWrapper(null),
      classLoader = checkNotNull(FakeGeneratedService::class.java.classLoader),
    )

    val metadata = starter.stop(
      P4aPythonRuntimeServiceControlSpec(
        packageName = "org.opencray.app",
        serviceId = "opencraypython",
        generatedServiceClassName = FakeGeneratedService::class.java.name,
      ),
    )

    assertEquals("stop_requested", metadata["launcherStopState"])
    assertEquals(FakeGeneratedService::class.java.name, metadata["launcherStopServiceClass"])
    assertEquals(listOf("stop"), FakeGeneratedService.invocations)
  }

  private fun launchRequest(
    runtimeRoot: Path,
    requestId: String,
  ): P4aPythonRuntime.P4aPythonLaunchRequest = P4aPythonRuntime.P4aPythonLaunchRequest(
    bridgeRequest = P4aPythonRuntime.P4aPythonExecBridgeRequest(
      requestId = requestId,
      taskId = "task-$requestId",
      workspaceRoot = runtimeRoot.resolve("workspace").toString(),
      scriptPath = runtimeRoot.resolve("workspace/demo.py").toString(),
      args = listOf("hello"),
      timeoutMs = 30_000L,
      requestedAtEpochMs = 123L,
    ),
    requestPath = runtimeRoot.resolve("requests/$requestId.json"),
    resultPath = runtimeRoot.resolve("results/$requestId.json"),
    logPath = runtimeRoot.resolve("logs/$requestId.log"),
    servicePollIntervalMs = 25L,
    runOnce = true,
  )
}

private class FakeGeneratedService {
  companion object {
    val invocations: MutableList<String> = mutableListOf()
    var capturedServiceArgument: String? = null
    var prepareShouldThrow: Boolean = false

    @JvmStatic
    fun reset() {
      invocations.clear()
      capturedServiceArgument = null
      prepareShouldThrow = false
    }

    @JvmStatic
    fun prepare(context: Context) {
      invocations += "prepare"
      if (prepareShouldThrow) {
        throw IllegalStateException("prepare boom")
      }
    }

    @JvmStatic
    fun start(context: Context, pythonServiceArgument: String) {
      invocations += "start"
      capturedServiceArgument = pythonServiceArgument
    }

    @JvmStatic
    fun stop(context: Context) {
      invocations += "stop"
    }
  }
}
