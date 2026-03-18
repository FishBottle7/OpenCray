package com.opencray.app

import android.content.Context
import java.lang.reflect.Modifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object P4aPythonRuntimeServiceContract {
  const val GENERATED_SERVICE_ID: String = "opencraypython"
  internal const val SERVICE_ARGUMENT_SCHEMA_VERSION: Int = 1
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
    val runtimeRoot = request.requestPath.parent.parent.toString()
    return P4aPythonRuntimeServiceStartSpec(
      packageName = packageName,
      serviceId = GENERATED_SERVICE_ID,
      generatedServiceClassName = generatedServiceClassName(
        packageName = packageName,
        serviceId = GENERATED_SERVICE_ID,
      ),
      serviceArgument = serviceArgumentJson.encodeToString(
        P4aPythonRuntimeServiceArgument(
          schemaVersion = SERVICE_ARGUMENT_SCHEMA_VERSION,
          runtimeRoot = runtimeRoot,
          requestId = request.bridgeRequest.requestId,
          requestPath = request.requestPath.toString(),
          resultPath = request.resultPath.toString(),
          logPath = request.logPath.toString(),
        ),
      ),
    )
  }
}

@Serializable
internal data class P4aPythonRuntimeServiceArgument(
  val schemaVersion: Int = P4aPythonRuntimeServiceContract.SERVICE_ARGUMENT_SCHEMA_VERSION,
  val runtimeRoot: String,
  val requestId: String,
  val requestPath: String,
  val resultPath: String,
  val logPath: String,
)

internal data class P4aPythonRuntimeServiceStartSpec(
  val packageName: String,
  val serviceId: String,
  val generatedServiceClassName: String,
  val serviceArgument: String,
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
      Class.forName(spec.generatedServiceClassName, true, classLoader)
    } catch (_: ClassNotFoundException) {
      return P4aPythonRuntimeServiceStartResult.Unavailable(
        reason = "service_class_missing",
        message = "Generated p4a service class was not found in the installed runtime artifact.",
      )
    } catch (error: Throwable) {
      return P4aPythonRuntimeServiceStartResult.Unavailable(
        reason = "service_class_load_failed",
        message = error.message ?: "Failed to load the generated p4a service class.",
      )
    }

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
}
