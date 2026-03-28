package com.opencray.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object P4aPythonRuntimeServiceContract {
  const val GENERATED_SERVICE_ID: String = "opencraypython"
  internal const val SERVICE_ARGUMENT_SCHEMA_VERSION: Int = 1
  internal const val SERVICE_START_ARGUMENT_FILE_NAME: String = "service-start-argument.json"
  internal const val DEFAULT_NOTIFICATION_ICON_NAME: String = "ic_python_runtime_notification"
  internal const val DEFAULT_NOTIFICATION_TITLE: String = "OpenCray Python Runtime"
  internal const val DEFAULT_NOTIFICATION_TEXT: String = "Running embedded Python service"
  private val serviceArgumentJson: Json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun generatedServiceClassName(
    packageName: String,
    serviceId: String = GENERATED_SERVICE_ID,
  ): String = buildString {
    append(packageName)
    append(".Service")
    append(
      serviceId.replaceFirstChar { first ->
        if (first.isLowerCase()) {
          first.titlecase()
        } else {
          first.toString()
        }
      },
    )
  }

  fun buildStartSpec(
    packageName: String,
    request: P4aPythonRuntime.P4aPythonLaunchRequest,
  ): P4aPythonRuntimeServiceStartSpec {
    val serviceArgument = buildServiceArgument(request)
    return P4aPythonRuntimeServiceStartSpec(
      packageName = packageName,
      serviceId = GENERATED_SERVICE_ID,
      generatedServiceClassName = generatedServiceClassName(
        packageName = packageName,
        serviceId = GENERATED_SERVICE_ID,
      ),
      serviceArgument = serviceArgumentJson.encodeToString(serviceArgument),
    )
  }

  fun buildServiceArgument(
    request: P4aPythonRuntime.P4aPythonLaunchRequest,
  ): P4aPythonRuntimeServiceArgument {
    val runtimeRoot = request.requestPath.parent.parent.toString()
    return P4aPythonRuntimeServiceArgument(
      schemaVersion = SERVICE_ARGUMENT_SCHEMA_VERSION,
      runtimeRoot = runtimeRoot,
      requestId = request.bridgeRequest.requestId,
      requestPath = request.requestPath.toString(),
      resultPath = request.resultPath.toString(),
      logPath = request.logPath.toString(),
      pollIntervalMs = request.servicePollIntervalMs,
      once = request.runOnce,
    )
  }

  fun encodeServiceArgument(
    argument: P4aPythonRuntimeServiceArgument,
  ): String = serviceArgumentJson.encodeToString(argument)

  fun buildControlSpec(
    packageName: String,
    serviceId: String = GENERATED_SERVICE_ID,
  ): P4aPythonRuntimeServiceControlSpec = P4aPythonRuntimeServiceControlSpec(
    packageName = packageName,
    serviceId = serviceId,
    generatedServiceClassName = generatedServiceClassName(
      packageName = packageName,
      serviceId = serviceId,
    ),
  )

  fun serviceStartArgumentPath(runtimeRoot: Path): Path =
    runtimeRoot.resolve("service_state").resolve(SERVICE_START_ARGUMENT_FILE_NAME)
}

@Serializable
internal data class P4aPythonRuntimeServiceArgument(
  val schemaVersion: Int = P4aPythonRuntimeServiceContract.SERVICE_ARGUMENT_SCHEMA_VERSION,
  val runtimeRoot: String,
  val requestId: String,
  val requestPath: String,
  val resultPath: String,
  val logPath: String,
  val pollIntervalMs: Long,
  val once: Boolean,
)

internal data class P4aPythonRuntimeServiceStartSpec(
  val packageName: String,
  val serviceId: String,
  val generatedServiceClassName: String,
  val serviceArgument: String,
)

internal data class P4aPythonRuntimeServiceControlSpec(
  val packageName: String,
  val serviceId: String,
  val generatedServiceClassName: String,
)

internal sealed interface P4aPythonRuntimeServiceStartResult {
  data class Started(
    val metadata: Map<String, String> = emptyMap(),
  ) : P4aPythonRuntimeServiceStartResult

  data class Unavailable(
    val reason: String,
    val message: String,
    val metadata: Map<String, String> = emptyMap(),
  ) : P4aPythonRuntimeServiceStartResult
}

internal interface P4aPythonRuntimeServiceStarter {
  fun start(spec: P4aPythonRuntimeServiceStartSpec): P4aPythonRuntimeServiceStartResult

  fun stop(spec: P4aPythonRuntimeServiceControlSpec): Map<String, String> = emptyMap()
}

internal object P4aPythonRuntimeExtractedPayloadRepair {
  private const val APP_ROOT_DIR_NAME: String = "app"
  private const val PRIVATE_VERSION_FILE_NAME: String = "private.version"
  private const val PYBUNDLE_VERSION_FILE_NAME: String = "libpybundle.version"
  private const val ENV_VARS_FILE_NAME: String = "p4a_env_vars.txt"
  private const val SERVICE_ENTRYPOINT_DIR_NAME: String = "python_runner"
  private val serviceEntrypointFileNames: List<String> = listOf(
    "p4a_service_main.py",
    "p4a_service_main.pyc",
  )

  fun repairIfNeeded(context: Context): Map<String, String> = runCatching {
    val appRoot = context.applicationContext.filesDir.toPath().resolve(APP_ROOT_DIR_NAME)
    repairIfNeeded(appRoot)
  }.getOrElse { error ->
    mapOf(
      "launcherPayloadRepairState" to "skipped",
      "launcherPayloadRepairReason" to (error.message ?: error::class.java.simpleName),
    )
  }

  fun repairIfNeeded(appRoot: Path): Map<String, String> {
    val privatePayloadAvailable = hasPrivatePayload(appRoot)
    val pythonBundleAvailable = hasPythonBundle(appRoot)
    val missingPayloads = mutableListOf<String>()
    val clearedMarkers = mutableListOf<String>()

    if (!privatePayloadAvailable) {
      missingPayloads += "private_payload"
      clearVersionMarker(
        appRoot = appRoot,
        markerFileName = PRIVATE_VERSION_FILE_NAME,
        clearedMarkers = clearedMarkers,
      )
    }
    if (!pythonBundleAvailable) {
      missingPayloads += "python_bundle"
      clearVersionMarker(
        appRoot = appRoot,
        markerFileName = PYBUNDLE_VERSION_FILE_NAME,
        clearedMarkers = clearedMarkers,
      )
    }

    val repairState = when {
      missingPayloads.isEmpty() -> "not_needed"
      clearedMarkers.isNotEmpty() -> "markers_cleared"
      else -> "missing_payload"
    }
    return buildMap {
      put("launcherPayloadRepairState", repairState)
      if (missingPayloads.isNotEmpty()) {
        put("launcherPayloadMissing", missingPayloads.joinToString(separator = ","))
      }
      if (clearedMarkers.isNotEmpty()) {
        put("launcherPayloadClearedMarkers", clearedMarkers.joinToString(separator = ","))
      }
    }
  }

  private fun hasPrivatePayload(appRoot: Path): Boolean {
    val envVarsPath = appRoot.resolve(ENV_VARS_FILE_NAME)
    if (!Files.isRegularFile(envVarsPath)) {
      return false
    }
    val serviceDir = appRoot.resolve(SERVICE_ENTRYPOINT_DIR_NAME)
    return serviceEntrypointFileNames.any { fileName ->
      Files.isRegularFile(serviceDir.resolve(fileName))
    }
  }

  private fun hasPythonBundle(appRoot: Path): Boolean {
    val pythonBundleDir = appRoot.resolve("_python_bundle")
    return Files.isRegularFile(pythonBundleDir.resolve("stdlib.zip")) &&
      Files.isDirectory(pythonBundleDir.resolve("modules"))
  }

  private fun clearVersionMarker(
    appRoot: Path,
    markerFileName: String,
    clearedMarkers: MutableList<String>,
  ) {
    val markerPath = appRoot.resolve(markerFileName)
    if (Files.deleteIfExists(markerPath)) {
      clearedMarkers += markerFileName
    }
  }
}

internal class ServiceBackedP4aPythonRuntimeLauncher(
  private val packageName: String,
  private val serviceStarter: P4aPythonRuntimeServiceStarter,
) : P4aPythonRuntime.P4aPythonRuntimeLauncher {
  override fun launch(
    request: P4aPythonRuntime.P4aPythonLaunchRequest,
  ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult {
    val spec = P4aPythonRuntimeServiceContract.buildStartSpec(
      packageName = packageName,
      request = request,
    )
    return when (val outcome = serviceStarter.start(spec)) {
      is P4aPythonRuntimeServiceStartResult.Started -> {
        P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Dispatched(
          metadata = mapOf(
            "launcherState" to "service_started",
            "launcherServiceId" to spec.serviceId,
            "launcherServiceClass" to spec.generatedServiceClassName,
          ) + outcome.metadata,
        )
      }

      is P4aPythonRuntimeServiceStartResult.Unavailable -> {
        P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Unavailable(
          errorCode = P4aPythonRuntime.ERROR_P4A_RUNTIME_UNAVAILABLE,
          errorMessage = outcome.message,
          metadata = mapOf(
            "launcherState" to outcome.reason,
            "launcherServiceId" to spec.serviceId,
            "launcherServiceClass" to spec.generatedServiceClassName,
          ) + outcome.metadata,
        )
      }
    }
  }

  override fun stop(): Map<String, String> = serviceStarter.stop(
    P4aPythonRuntimeServiceContract.buildControlSpec(packageName = packageName),
  )

  companion object {
    fun fromContext(context: Context): ServiceBackedP4aPythonRuntimeLauncher =
      ServiceBackedP4aPythonRuntimeLauncher(
        packageName = context.applicationContext.packageName,
        serviceStarter = AndroidP4aPythonRuntimeServiceStarter(context),
      )
  }
}

internal class AndroidP4aPythonRuntimeServiceStarter(
  private val context: Context,
  private val classLoader: ClassLoader = context.javaClass.classLoader
    ?: AndroidP4aPythonRuntimeServiceStarter::class.java.classLoader
    ?: ClassLoader.getSystemClassLoader(),
) : P4aPythonRuntimeServiceStarter {
  override fun start(spec: P4aPythonRuntimeServiceStartSpec): P4aPythonRuntimeServiceStartResult {
    val generatedServiceClass = try {
      loadGeneratedServiceClass(spec.generatedServiceClassName)
    } catch (error: Throwable) {
      return P4aPythonRuntimeServiceStartResult.Unavailable(
        reason = "service_class_load_failed",
        message = error.message ?: "Failed to load the generated p4a service class.",
      )
    } ?: return P4aPythonRuntimeServiceStartResult.Unavailable(
      reason = "service_class_missing",
      message = "Generated p4a service class was not found in the installed runtime artifact.",
    )

    val prepareMethod = generatedServiceClass.methods.firstOrNull { method ->
      method.name == "prepare" &&
        Modifier.isStatic(method.modifiers) &&
        method.parameterTypes.size == 1 &&
        method.parameterTypes[0].isAssignableFrom(context.javaClass)
    }
    val prepareMetadata = linkedMapOf<String, String>()
    if (prepareMethod == null) {
      prepareMetadata["launcherPrepareState"] = "missing"
    } else {
      prepareMetadata.putAll(P4aPythonRuntimeExtractedPayloadRepair.repairIfNeeded(context))
      try {
        prepareMethod.invoke(null, context)
        prepareMetadata["launcherPrepareState"] = "prepared"
      } catch (error: Throwable) {
        return P4aPythonRuntimeServiceStartResult.Unavailable(
          reason = "service_prepare_failed",
          message = error.cause?.message ?: error.message ?: "Failed to prepare the embedded p4a runtime service.",
          metadata = prepareMetadata + mapOf(
            "launcherPrepareState" to "failed",
          ),
        )
      }
    }

    val directStartIntent = try {
      buildDirectStartIntent(
        generatedServiceClass = generatedServiceClass,
        spec = spec,
      )
    } catch (error: Throwable) {
      return P4aPythonRuntimeServiceStartResult.Unavailable(
        reason = "service_intent_build_failed",
        message = error.cause?.message ?: error.message ?: "Failed to prepare the embedded p4a service intent.",
        metadata = prepareMetadata,
      )
    }
    if (directStartIntent != null) {
      return try {
        val componentName = context.startForegroundService(directStartIntent)
        if (componentName == null) {
          P4aPythonRuntimeServiceStartResult.Unavailable(
            reason = "service_start_returned_null",
            message = "Android did not start the embedded p4a service.",
            metadata = prepareMetadata + mapOf(
              "launcherStartMode" to "foreground_service_direct_intent",
            ),
          )
        } else {
          P4aPythonRuntimeServiceStartResult.Started(
            metadata = prepareMetadata + mapOf(
              "launcherResolvedServiceClass" to spec.generatedServiceClassName,
              "launcherComponent" to componentName.flattenToShortString(),
              "launcherStartMode" to "foreground_service_direct_intent",
            ),
          )
        }
      } catch (error: Throwable) {
        P4aPythonRuntimeServiceStartResult.Unavailable(
          reason = "service_start_failed",
          message = error.cause?.message ?: error.message ?: "Failed to start the embedded p4a service.",
          metadata = prepareMetadata + mapOf(
            "launcherStartMode" to "foreground_service_direct_intent",
          ),
        )
      }
    }

    val startMethod = generatedServiceClass.methods.firstOrNull { method ->
      method.name == "start" &&
        Modifier.isStatic(method.modifiers) &&
        method.parameterTypes.size == 2 &&
        method.parameterTypes[1] == String::class.java &&
        method.parameterTypes[0].isAssignableFrom(context.javaClass)
    } ?: return P4aPythonRuntimeServiceStartResult.Unavailable(
      reason = "service_start_signature_missing",
      message = "Generated p4a service class does not expose a compatible start(Context, String) entrypoint.",
      metadata = mapOf(
        "availableMethods" to generatedServiceClass.methods.joinToString(separator = ",") { method ->
          method.name
        },
      ),
    )

    return try {
      startMethod.invoke(null, context, spec.serviceArgument)
      P4aPythonRuntimeServiceStartResult.Started(
        metadata = prepareMetadata + mapOf(
          "launcherResolvedServiceClass" to spec.generatedServiceClassName,
          "launcherStartMode" to "generated_static_start",
        ),
      )
    } catch (error: Throwable) {
      P4aPythonRuntimeServiceStartResult.Unavailable(
        reason = "service_start_failed",
        message = error.cause?.message ?: error.message ?: "Failed to start the embedded p4a service.",
        metadata = prepareMetadata,
      )
    }
  }

  override fun stop(spec: P4aPythonRuntimeServiceControlSpec): Map<String, String> {
    val generatedServiceClass = try {
      loadGeneratedServiceClass(spec.generatedServiceClassName)
    } catch (error: Throwable) {
      return mapOf(
        "launcherStopState" to "service_class_load_failed",
        "launcherStopServiceClass" to spec.generatedServiceClassName,
        "launcherStopError" to (error.message ?: "unknown"),
      )
    } ?: return mapOf(
      "launcherStopState" to "service_class_missing",
      "launcherStopServiceClass" to spec.generatedServiceClassName,
    )
    val stopMethod = generatedServiceClass.methods.firstOrNull { method ->
      method.name == "stop" &&
        Modifier.isStatic(method.modifiers) &&
        method.parameterTypes.size == 1 &&
        method.parameterTypes[0].isAssignableFrom(context.javaClass)
    } ?: return mapOf(
      "launcherStopState" to "stop_method_missing",
      "launcherStopServiceClass" to spec.generatedServiceClassName,
    )
    return try {
      stopMethod.invoke(null, context)
      mapOf(
        "launcherStopState" to "stop_requested",
        "launcherStopServiceClass" to spec.generatedServiceClassName,
      )
    } catch (error: Throwable) {
      mapOf(
        "launcherStopState" to "stop_failed",
        "launcherStopServiceClass" to spec.generatedServiceClassName,
        "launcherStopError" to (error.cause?.message ?: error.message ?: "unknown"),
      )
    }
  }

  private fun loadGeneratedServiceClass(className: String): Class<*>? = try {
    Class.forName(className, true, classLoader)
  } catch (_: ClassNotFoundException) {
    null
  } catch (error: Throwable) {
    throw error
  }

  private fun buildDirectStartIntent(
    generatedServiceClass: Class<*>,
    spec: P4aPythonRuntimeServiceStartSpec,
  ): Intent? {
    val defaultIntentMethod = generatedServiceClass.methods.firstOrNull { method ->
      method.name == "getDefaultIntent" &&
        Modifier.isStatic(method.modifiers) &&
        method.parameterTypes.size == 5 &&
        method.parameterTypes[0].isAssignableFrom(context.javaClass) &&
        Intent::class.java.isAssignableFrom(method.returnType)
    } ?: return null

    val startIntent = defaultIntentMethod.invoke(
      null,
      context,
      P4aPythonRuntimeServiceContract.DEFAULT_NOTIFICATION_ICON_NAME,
      P4aPythonRuntimeServiceContract.DEFAULT_NOTIFICATION_TITLE,
      P4aPythonRuntimeServiceContract.DEFAULT_NOTIFICATION_TEXT,
      spec.serviceArgument,
    ) as? Intent ?: return null

    startIntent.putExtra("pythonServiceArgument", spec.serviceArgument)
    startIntent.putExtra("serviceStartAsForeground", "true")
    return startIntent
  }
}
