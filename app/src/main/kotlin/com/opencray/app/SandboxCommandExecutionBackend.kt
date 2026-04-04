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
) {
  fun metadata(): Map<String, String> = mapOf(
    "sandboxCommandBackendKind" to backendKind,
    "sandboxCommandProviderNative" to providerNative.toString(),
    "sandboxCommandSupportsStreamingLogs" to supportsStreamingLogs.toString(),
    "sandboxCommandSupportsReconnect" to supportsReconnect.toString(),
    "sandboxCommandSupportsManagedProcessLiveObservation" to
      supportsManagedProcessLiveObservation.toString(),
    "sandboxCommandSupportsManagedProcessObservationCursorResume" to
      supportsManagedProcessObservationCursorResume.toString(),
    "sandboxCommandSupportsManagedProcessObservationBackfill" to
      supportsManagedProcessObservationBackfill.toString(),
  )
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
