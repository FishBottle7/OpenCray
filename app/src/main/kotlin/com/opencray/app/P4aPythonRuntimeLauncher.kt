package com.opencray.app

import android.content.Context
import android.content.Intent
import android.os.Build

internal object P4aPythonRuntimeServiceContract {
  const val ACTION_START_RUNTIME: String = "org.opencray.app.action.P4A_START_RUNTIME"
  const val EXTRA_RUNTIME_ROOT: String = "org.opencray.app.extra.P4A_RUNTIME_ROOT"
  const val EXTRA_REQUEST_ID: String = "org.opencray.app.extra.P4A_REQUEST_ID"
  const val EXTRA_REQUEST_PATH: String = "org.opencray.app.extra.P4A_REQUEST_PATH"
  const val EXTRA_RESULT_PATH: String = "org.opencray.app.extra.P4A_RESULT_PATH"
  const val EXTRA_LOG_PATH: String = "org.opencray.app.extra.P4A_LOG_PATH"

  fun buildStartSpec(
    packageName: String,
    request: P4aPythonRuntime.P4aPythonLaunchRequest,
  ): P4aPythonRuntimeServiceStartSpec = P4aPythonRuntimeServiceStartSpec(
    action = ACTION_START_RUNTIME,
    packageName = packageName,
    extras = mapOf(
      EXTRA_RUNTIME_ROOT to request.requestPath.parent.parent.toString(),
      EXTRA_REQUEST_ID to request.bridgeRequest.requestId,
      EXTRA_REQUEST_PATH to request.requestPath.toString(),
      EXTRA_RESULT_PATH to request.resultPath.toString(),
      EXTRA_LOG_PATH to request.logPath.toString(),
    ),
  )
}

internal data class P4aPythonRuntimeServiceStartSpec(
  val action: String,
  val packageName: String,
  val extras: Map<String, String>,
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
            "launcherAction" to spec.action,
            "launcherPackage" to spec.packageName,
          ) + outcome.metadata,
        )
      }

      is P4aPythonRuntimeServiceStartResult.Unavailable -> {
        P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Unavailable(
          errorCode = P4aPythonRuntime.ERROR_P4A_RUNTIME_UNAVAILABLE,
          errorMessage = outcome.message,
          metadata = mapOf(
            "launcherState" to outcome.reason,
            "launcherAction" to spec.action,
            "launcherPackage" to spec.packageName,
          ) + outcome.metadata,
        )
      }
    }
  }

  companion object {
    fun fromContext(context: Context): ServiceBackedP4aPythonRuntimeLauncher =
      ServiceBackedP4aPythonRuntimeLauncher(
        packageName = context.applicationContext.packageName,
        serviceStarter = AndroidP4aPythonRuntimeServiceStarter(context.applicationContext),
      )
  }
}

internal class AndroidP4aPythonRuntimeServiceStarter(
  private val context: Context,
) : P4aPythonRuntimeServiceStarter {
  override fun start(spec: P4aPythonRuntimeServiceStartSpec): P4aPythonRuntimeServiceStartResult {
    val intent = Intent(spec.action).apply {
      `package` = spec.packageName
      spec.extras.forEach { (key, value) ->
        putExtra(key, value)
      }
    }
    val resolvedService = context.packageManager.resolveService(intent, 0)
      ?: return P4aPythonRuntimeServiceStartResult.Unavailable(
        reason = "service_unresolved",
        message = "No Android service is registered for the embedded Python runtime action.",
      )

    val component = try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    } catch (error: Throwable) {
      return P4aPythonRuntimeServiceStartResult.Unavailable(
        reason = "service_start_failed",
        message = error.message ?: "Failed to start the embedded Python runtime service.",
      )
    }

    if (component == null) {
      return P4aPythonRuntimeServiceStartResult.Unavailable(
        reason = "service_start_failed",
        message = "Embedded Python runtime service start returned null.",
      )
    }

    return P4aPythonRuntimeServiceStartResult.Started(
      metadata = mapOf(
        "launcherResolvedService" to resolvedService.serviceInfo.name.orEmpty(),
        "launcherComponent" to component.className.orEmpty(),
      ),
    )
  }
}
