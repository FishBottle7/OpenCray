package com.opencray.app

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
    assertEquals("org.opencray.app.action.P4A_START_RUNTIME", unavailable.metadata["launcherAction"])
    assertEquals("org.opencray.app", unavailable.metadata["launcherPackage"])
    assertEquals("not-registered", unavailable.metadata["detail"])

    val spec = checkNotNull(capturedSpec)
    assertEquals("org.opencray.app.action.P4A_START_RUNTIME", spec.action)
    assertEquals("org.opencray.app", spec.packageName)
    assertEquals(runtimeRoot.toString(), spec.extras[P4aPythonRuntimeServiceContract.EXTRA_RUNTIME_ROOT])
    assertEquals("request-missing", spec.extras[P4aPythonRuntimeServiceContract.EXTRA_REQUEST_ID])
    assertEquals(
      runtimeRoot.resolve("requests/request-missing.json").toString(),
      spec.extras[P4aPythonRuntimeServiceContract.EXTRA_REQUEST_PATH],
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
              "launcherResolvedService" to "org.kivy.android.PythonService",
              "launcherComponent" to "org.opencray.app/.PythonService",
            ),
          )
      },
    )

    val result = launcher.launch(launchRequest)

    assertTrue(result is P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Dispatched)
    val dispatched = result as P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Dispatched
    assertEquals("service_started", dispatched.metadata["launcherState"])
    assertEquals("org.opencray.app.action.P4A_START_RUNTIME", dispatched.metadata["launcherAction"])
    assertEquals("org.opencray.app", dispatched.metadata["launcherPackage"])
    assertEquals("org.kivy.android.PythonService", dispatched.metadata["launcherResolvedService"])
    assertEquals("org.opencray.app/.PythonService", dispatched.metadata["launcherComponent"])
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
  )
}
