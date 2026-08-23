package com.opencray.app

import com.opencray.app.e2b.E2BEnvdCommandTransport
import com.opencray.app.e2b.E2BMinimalNativeForegroundCommandProcessRunner
import com.opencray.app.e2b.E2BMinimalProtocolManagedProcessControllerFactory
import com.opencray.app.e2b.E2BSandboxActivityTracker
import com.opencray.app.e2b.SharedE2BSandboxActivityTracker
import com.opencray.app.e2b.UrlConnectionE2BEnvdCommandTransport
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.CommandApprovalToken
import com.opencray.runtime.CommandExecutionRequest
import com.opencray.runtime.CommandExecutor
import com.opencray.runtime.PythonScriptRuntime
import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ReconnectableManagedProcessControllerFactory
import java.nio.file.Path
import kotlinx.serialization.json.Json

private const val E2B_NATIVE_COMMAND_PROVIDER_RESUME_CONTRACT: String =
  "host_buffered_seed_then_live_attach"
private const val E2B_NATIVE_COMMAND_PROVIDER_RESUME_BLOCKER: String =
  "envd_connect_request_selector_only"

internal class E2BMinimalProtocolSandboxCommandExecutionBackend(
  private val workspaceRootProvider: () -> Path,
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val sessionStore: E2BSandboxSessionStore,
  private val activeSessionProvider: () -> E2BSandboxSessionSnapshot?,
  private val pythonRuntime: PythonScriptRuntime,
  private val transport: E2BEnvdCommandTransport = UrlConnectionE2BEnvdCommandTransport(),
  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
  private val activityTracker: E2BSandboxActivityTracker = SharedE2BSandboxActivityTracker,
) : SandboxCommandExecutionBackend {
  override val capabilities: SandboxCommandBackendCapabilities = SandboxCommandBackendCapabilities(
    backendKind = "provider_native",
    providerNative = true,
    supportsStreamingLogs = false,
    supportsReconnect = true,
    supportsManagedProcessLiveObservation = true,
    supportsManagedProcessObservationCursorResume = false,
    supportsManagedProcessObservationBackfill = false,
    providerObservationResumeContract = E2B_NATIVE_COMMAND_PROVIDER_RESUME_CONTRACT,
    providerObservationResumeBlocker = E2B_NATIVE_COMMAND_PROVIDER_RESUME_BLOCKER,
  )

  override fun createCommandExecutor(): CommandExecutor {
    val delegate = CommandExecutor(
      runner = E2BMinimalNativeForegroundCommandProcessRunner(
        workspaceRootProvider = workspaceRootProvider,
        settingsProvider = settingsProvider,
        sessionStore = sessionStore,
        activeSessionProvider = activeSessionProvider,
        fallbackRunnerProvider = {
          PythonBackedCommandProcessRunner(
            workspaceRoot = workspaceRootProvider(),
            pythonRuntime = pythonRuntime,
            json = json,
          )
        },
        transport = transport,
        capabilities = capabilities,
        json = json,
      ),
    )
    return object : CommandExecutor() {
      override fun execute(
        request: CommandExecutionRequest,
        policyDecision: PolicyDecision,
        approvalToken: CommandApprovalToken?,
        hooks: RuntimeExecutionHooks,
      ): ExecutionResult {
        val backendTraceMetadata = SandboxExecutionTraceMetadata.backendMetadata(
          metadata = request.metadata,
          backendKind = capabilities.backendKind,
        )
        val providerTraceMetadata = SandboxExecutionTraceMetadata.providerStartMetadata(
          request.metadata + backendTraceMetadata,
        )
        val tracedRequest = request.copy(
          metadata = request.metadata + backendTraceMetadata + providerTraceMetadata,
        )
        val result = delegate.execute(
          request = tracedRequest,
          policyDecision = policyDecision,
          approvalToken = approvalToken,
          hooks = hooks,
        )
        return result.copy(
          metadata = result.metadata + backendTraceMetadata + providerTraceMetadata,
        )
      }
    }
  }

  override fun createManagedProcessControllerFactory(): ManagedProcessControllerFactory {
    val delegate: ReconnectableManagedProcessControllerFactory =
      E2BMinimalProtocolManagedProcessControllerFactory(
        workspaceRootProvider = workspaceRootProvider,
        settingsProvider = settingsProvider,
        sessionStore = sessionStore,
        activeSessionProvider = activeSessionProvider,
        fallbackFactoryProvider = {
          PythonBackedSandboxCommandExecutionBackend(
            workspaceRootProvider = workspaceRootProvider,
            pythonRuntime = pythonRuntime,
            json = json,
          ).createManagedProcessControllerFactory()
        },
        transport = transport,
        capabilities = capabilities,
        json = json,
        activityTracker = activityTracker,
      )
    return object : ReconnectableManagedProcessControllerFactory {
      override fun start(request: ManagedProcessStartRequest): ManagedProcessController {
        val backendTraceMetadata = SandboxExecutionTraceMetadata.backendMetadata(
          metadata = request.metadata,
          backendKind = capabilities.backendKind,
        )
        val providerTraceMetadata = SandboxExecutionTraceMetadata.providerStartMetadata(
          request.metadata + backendTraceMetadata,
        )
        val controller = delegate.start(
          request.copy(
            metadata = request.metadata + backendTraceMetadata + providerTraceMetadata,
          ),
        )
        return decorateController(
          controller = controller,
          decorationMetadata = backendTraceMetadata + providerTraceMetadata,
        )
      }

      override fun reconnect(snapshot: ManagedProcessSnapshot): ManagedProcessController? {
        val reconnectTraceMetadata = SandboxExecutionTraceMetadata.reconnectMetadata(snapshot.metadata)
        val backendTraceMetadata = SandboxExecutionTraceMetadata.backendMetadata(
          metadata = snapshot.metadata + reconnectTraceMetadata,
          backendKind = capabilities.backendKind,
        )
        val providerTraceMetadata = SandboxExecutionTraceMetadata.providerConnectMetadata(
          snapshot.metadata + reconnectTraceMetadata + backendTraceMetadata,
        )
        val controller = delegate.reconnect(
          snapshot.copy(
            metadata =
              snapshot.metadata +
                reconnectTraceMetadata +
                backendTraceMetadata +
                providerTraceMetadata,
          ),
        ) ?: return null
        return decorateController(
          controller = controller,
          decorationMetadata =
            reconnectTraceMetadata +
              backendTraceMetadata +
              providerTraceMetadata,
        )
      }

      private fun decorateController(
        controller: ManagedProcessController,
        decorationMetadata: Map<String, String>,
      ): ManagedProcessController {
        return object : ManagedProcessController {
          override fun snapshot(): ManagedProcessSnapshot = controller.snapshot().withDecorationMetadata()

          override fun await(timeoutMs: Long): ManagedProcessSnapshot =
            controller.await(timeoutMs).withDecorationMetadata()

          override fun terminate(): ManagedProcessSnapshot =
            controller.terminate().withDecorationMetadata()

          private fun ManagedProcessSnapshot.withDecorationMetadata(): ManagedProcessSnapshot =
            copy(metadata = metadata + decorationMetadata)
        }
      }
    }
  }
}
