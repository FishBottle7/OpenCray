package com.opencray.app

import android.content.Context
import java.lang.reflect.Modifier
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object P4aPythonRuntimeServiceContract {
  const val GENERATED_SERVICE_ID: String = "opencraypython"
  internal const val SERVICE_ARGUMENT_SCHEMA_VERSION: Int = 1
  internal const val SERVICE_START_ARGUMENT_FILE_NAME: String = "service-start-argument.json"
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
}
