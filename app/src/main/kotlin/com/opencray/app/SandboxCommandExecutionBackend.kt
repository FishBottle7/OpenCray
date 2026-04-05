package com.opencray.app

import com.opencray.runtime.CommandExecutor
import com.opencray.runtime.PythonScriptRuntime
import com.opencray.runtime.process.ManagedProcessControllerFactory
import java.nio.file.Path
import kotlinx.serialization.json.Json

internal data class SandboxCommandBackendCapabilities(
  val backendKind: String,
  val providerNative: Boolean,
  val supportsStreamingLogs: Boolean,
  val supportsReconnect: Boolean,
  val supportsManagedProcessLiveObservation: Boolean = false,
  val supportsManagedProcessObservationCursorResume: Boolean = false,
  val supportsManagedProcessObservationBackfill: Boolean = false,
  val providerObservationResumeContract: String? = null,
  val providerObservationResumeBlocker: String? = null,
) {
  fun metadata(): Map<String, String> = buildMap {
    put("sandboxCommandBackendKind", backendKind)
    put("sandboxCommandProviderNative", providerNative.toString())
    put("sandboxCommandSupportsStreamingLogs", supportsStreamingLogs.toString())
    put("sandboxCommandSupportsReconnect", supportsReconnect.toString())
    put(
      "sandboxCommandSupportsManagedProcessLiveObservation",
      supportsManagedProcessLiveObservation.toString(),
    )
    put(
      "sandboxCommandSupportsManagedProcessObservationCursorResume",
      supportsManagedProcessObservationCursorResume.toString(),
    )
    put(
      "sandboxCommandSupportsManagedProcessObservationBackfill",
      supportsManagedProcessObservationBackfill.toString(),
    )
    providerObservationResumeContract
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { contract ->
        put("sandboxCommandProviderObservationResumeContract", contract)
      }
    providerObservationResumeBlocker
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { blocker ->
        put("sandboxCommandProviderObservationResumeBlocker", blocker)
      }
  }
}

internal interface SandboxCommandExecutionBackend {
  val capabilities: SandboxCommandBackendCapabilities

  fun createCommandExecutor(): CommandExecutor

  fun createManagedProcessControllerFactory(): ManagedProcessControllerFactory
}

internal class PythonBackedSandboxCommandExecutionBackend(
  private val workspaceRootProvider: () -> Path,
  private val pythonRuntime: PythonScriptRuntime,
  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : SandboxCommandExecutionBackend {
  override val capabilities: SandboxCommandBackendCapabilities = SandboxCommandBackendCapabilities(
    backendKind = "python_wrapper",
    providerNative = false,
    supportsStreamingLogs = false,
    supportsReconnect = false,
  )

  override fun createCommandExecutor(): CommandExecutor = CommandExecutor(
    runner = PythonBackedCommandProcessRunner(
      workspaceRoot = workspaceRootProvider(),
      pythonRuntime = pythonRuntime,
      json = json,
      capabilities = capabilities,
    ),
  )

  override fun createManagedProcessControllerFactory(): ManagedProcessControllerFactory =
    SandboxPythonManagedCommandControllerFactory(
      workspaceRoot = workspaceRootProvider(),
      pythonRuntime = pythonRuntime,
      json = json,
      capabilities = capabilities,
    )
}
