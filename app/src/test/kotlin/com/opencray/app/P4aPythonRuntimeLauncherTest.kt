package com.opencray.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    assertTrue(result.toString(), result is P4aPythonRuntimeServiceStartResult.Started)
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

  @Test
  fun androidServiceStarterBuildsDirectIntentWithNotificationIcon() {
    FakeGeneratedForegroundService.reset()
    val starter = AndroidP4aPythonRuntimeServiceStarter(
      context = ContextWrapper(null),
      classLoader = checkNotNull(FakeGeneratedForegroundService::class.java.classLoader),
    )
    val buildDirectStartIntent = AndroidP4aPythonRuntimeServiceStarter::class.java.getDeclaredMethod(
      "buildDirectStartIntent",
      Class::class.java,
      P4aPythonRuntimeServiceStartSpec::class.java,
    ).apply {
      isAccessible = true
    }
    val serviceArgument = """{"runtimeRoot":"/tmp/opencray-python"}"""
    val spec = P4aPythonRuntimeServiceStartSpec(
      packageName = "org.opencray.app",
      serviceId = "opencraypython",
      generatedServiceClassName = FakeGeneratedForegroundService::class.java.name,
      serviceArgument = serviceArgument,
    )

    val intent = buildDirectStartIntent.invoke(
      starter,
      FakeGeneratedForegroundService::class.java,
      spec,
    ) as? Intent

    assertNotNull(intent)
    assertEquals(
      listOf("getDefaultIntent"),
      FakeGeneratedForegroundService.invocations,
    )
    assertEquals(
      P4aPythonRuntimeServiceContract.DEFAULT_NOTIFICATION_ICON_NAME,
      FakeGeneratedForegroundService.capturedNotificationIconName,
    )
    assertEquals(
      P4aPythonRuntimeServiceContract.DEFAULT_NOTIFICATION_TITLE,
      FakeGeneratedForegroundService.capturedNotificationTitle,
    )
    assertEquals(
      P4aPythonRuntimeServiceContract.DEFAULT_NOTIFICATION_TEXT,
      FakeGeneratedForegroundService.capturedNotificationText,
    )
    assertEquals(
      serviceArgument,
      FakeGeneratedForegroundService.capturedServiceArgument,
    )
    assertEquals(
      serviceArgument,
      intent?.getStringExtra("pythonServiceArgument"),
    )
    assertEquals("true", intent?.getStringExtra("serviceStartAsForeground"))
  }

  @Test
  fun payloadRepairClearsPybundleMarkerWhenPythonBundleIsMissing() {
    val appRoot = temporaryFolder.newFolder("p4a-app-missing-pybundle").toPath()
    val privateVersion = appRoot.resolve("private.version")
    val pybundleVersion = appRoot.resolve("libpybundle.version")
    val serviceDir = Files.createDirectories(appRoot.resolve("python_runner"))
    Files.write(privateVersion, "version".toByteArray())
    Files.write(pybundleVersion, "version".toByteArray())
    Files.write(appRoot.resolve("p4a_env_vars.txt"), "env".toByteArray())
    Files.write(serviceDir.resolve("p4a_service_main.pyc"), "compiled".toByteArray())

    val metadata = P4aPythonRuntimeExtractedPayloadRepair.repairIfNeeded(appRoot)

    assertEquals("markers_cleared", metadata["launcherPayloadRepairState"])
    assertEquals("python_bundle", metadata["launcherPayloadMissing"])
    assertEquals("libpybundle.version", metadata["launcherPayloadClearedMarkers"])
    assertTrue(Files.exists(privateVersion))
    assertTrue(Files.notExists(pybundleVersion))
  }

  @Test
  fun payloadRepairClearsPrivateMarkerWhenPrivatePayloadIsMissing() {
    val appRoot = temporaryFolder.newFolder("p4a-app-missing-private").toPath()
    val privateVersion = appRoot.resolve("private.version")
    val pybundleVersion = appRoot.resolve("libpybundle.version")
    val pythonBundleDir = Files.createDirectories(appRoot.resolve("_python_bundle").resolve("modules"))
    Files.write(privateVersion, "version".toByteArray())
    Files.write(pybundleVersion, "version".toByteArray())
    Files.write(pythonBundleDir.parent.resolve("stdlib.zip"), "stdlib".toByteArray())

    val metadata = P4aPythonRuntimeExtractedPayloadRepair.repairIfNeeded(appRoot)

    assertEquals("markers_cleared", metadata["launcherPayloadRepairState"])
    assertEquals("private_payload", metadata["launcherPayloadMissing"])
    assertEquals("private.version", metadata["launcherPayloadClearedMarkers"])
    assertTrue(Files.notExists(privateVersion))
    assertTrue(Files.exists(pybundleVersion))
  }

  @Test
  fun payloadRepairLeavesVersionMarkersWhenPayloadIsComplete() {
    val appRoot = temporaryFolder.newFolder("p4a-app-complete").toPath()
    val privateVersion = appRoot.resolve("private.version")
    val pybundleVersion = appRoot.resolve("libpybundle.version")
    val serviceDir = Files.createDirectories(appRoot.resolve("python_runner"))
    val modulesDir = Files.createDirectories(appRoot.resolve("_python_bundle").resolve("modules"))
    Files.write(privateVersion, "version".toByteArray())
    Files.write(pybundleVersion, "version".toByteArray())
    Files.write(appRoot.resolve("p4a_env_vars.txt"), "env".toByteArray())
    Files.write(serviceDir.resolve("p4a_service_main.pyc"), "compiled".toByteArray())
    Files.write(modulesDir.parent.resolve("stdlib.zip"), "stdlib".toByteArray())

    val metadata = P4aPythonRuntimeExtractedPayloadRepair.repairIfNeeded(appRoot)

    assertEquals("not_needed", metadata["launcherPayloadRepairState"])
    assertTrue(Files.exists(privateVersion))
    assertTrue(Files.exists(pybundleVersion))
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

private class FakeGeneratedForegroundService {
  companion object {
    val invocations: MutableList<String> = mutableListOf()
    var capturedNotificationIconName: String? = null
    var capturedNotificationTitle: String? = null
    var capturedNotificationText: String? = null
    var capturedServiceArgument: String? = null

    @JvmStatic
    fun reset() {
      invocations.clear()
      capturedNotificationIconName = null
      capturedNotificationTitle = null
      capturedNotificationText = null
      capturedServiceArgument = null
    }

    @JvmStatic
    fun prepare(context: Context) {
      invocations += "prepare"
    }

    @JvmStatic
    fun getDefaultIntent(
      context: Context,
      notificationIconName: String,
      notificationTitle: String,
      notificationText: String,
      pythonServiceArgument: String,
    ): Intent {
      invocations += "getDefaultIntent"
      capturedNotificationIconName = notificationIconName
      capturedNotificationTitle = notificationTitle
      capturedNotificationText = notificationText
      capturedServiceArgument = pythonServiceArgument
      return FakeForegroundStartIntent()
    }
  }
}

private class FakeForegroundStartIntent : Intent() {
  private val extras: MutableMap<String, String?> = linkedMapOf()

  override fun putExtra(name: String?, value: String?): Intent {
    if (name != null) {
      extras[name] = value
    }
    return this
  }

  override fun getStringExtra(name: String?): String? = name?.let(extras::get)
}
