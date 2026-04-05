package com.opencray.app

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

internal data class SandboxCommandBackendCandidate(
  val backend: SandboxCommandExecutionBackend? = null,
  val unavailableReasonCode: String? = null,
  val unavailableDetail: String? = null,
) {
  val available: Boolean
    get() = backend != null

  init {
    require(backend != null || !unavailableReasonCode.isNullOrBlank()) {
      "SandboxCommandBackendCandidate must provide a backend or an unavailable reason code."
    }
  }

  companion object {
    fun unavailable(
      reasonCode: String,
      detail: String,
    ): SandboxCommandBackendCandidate = SandboxCommandBackendCandidate(
      backend = null,
      unavailableReasonCode = reasonCode,
      unavailableDetail = detail,
    )
  }
}

internal data class SandboxCommandBackendSelection(
  val requestedKind: String,
  val resolvedKind: String,
  val providerNativeRequested: Boolean,
  val providerNativeAvailable: Boolean,
  val fallbackReasonCode: String? = null,
  val fallbackDetail: String? = null,
) {
  fun metadata(): Map<String, String> = buildMap {
    put("sandboxCommandBackendRequestedKind", requestedKind)
    put("sandboxCommandBackendResolvedKind", resolvedKind)
    put("sandboxCommandProviderNativeRequested", providerNativeRequested.toString())
    put("sandboxCommandProviderNativeAvailable", providerNativeAvailable.toString())
    fallbackReasonCode?.let { put("sandboxCommandBackendFallbackReasonCode", it) }
    fallbackDetail?.takeIf(String::isNotBlank)?.let { put("sandboxCommandBackendFallbackDetail", it) }
  }
}

internal object E2BSandboxCommandExecutionBackendFactory {
  const val REQUESTED_KIND_PROVIDER_NATIVE_PREFERRED: String = "provider_native_preferred"
  const val REASON_PROVIDER_NATIVE_TRANSPORT_STACK_MISSING: String =
    "provider_native_transport_stack_missing"
  private const val DETAIL_PROVIDER_NATIVE_TRANSPORT_STACK_MISSING: String =
    "E2B native commands require envd Connect RPC/protobuf client support, which is not wired in this Android build yet."

  fun create(
    workspaceRootProvider: () -> Path,
    settingsProvider: () -> ResolvedSandboxSettings,
    sessionStore: E2BSandboxSessionStore,
    activeSessionProvider: () -> E2BSandboxSessionSnapshot?,
    pythonRuntime: PythonScriptRuntime,
    json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
  ): SandboxCommandExecutionBackend = E2BMinimalProtocolSandboxCommandExecutionBackend(
    workspaceRootProvider = workspaceRootProvider,
    settingsProvider = settingsProvider,
    sessionStore = sessionStore,
    activeSessionProvider = activeSessionProvider,
    pythonRuntime = pythonRuntime,
    json = json,
  )

  fun create(
    fallbackBackend: SandboxCommandExecutionBackend,
    providerNativeCandidate: SandboxCommandBackendCandidate = defaultProviderNativeCandidate(),
  ): SandboxCommandExecutionBackend {
    require(!fallbackBackend.capabilities.providerNative) {
      "Fallback sandbox command backend must not declare provider-native capabilities."
    }
    require(providerNativeCandidate.backend == null || providerNativeCandidate.backend.capabilities.providerNative) {
      "Provider-native sandbox command backend candidate must declare provider-native capabilities."
    }
    val delegate = providerNativeCandidate.backend ?: fallbackBackend
    val selection = if (providerNativeCandidate.available) {
      SandboxCommandBackendSelection(
        requestedKind = REQUESTED_KIND_PROVIDER_NATIVE_PREFERRED,
        resolvedKind = delegate.capabilities.backendKind,
        providerNativeRequested = true,
        providerNativeAvailable = true,
      )
    } else {
      SandboxCommandBackendSelection(
        requestedKind = REQUESTED_KIND_PROVIDER_NATIVE_PREFERRED,
        resolvedKind = fallbackBackend.capabilities.backendKind,
        providerNativeRequested = true,
        providerNativeAvailable = false,
        fallbackReasonCode = providerNativeCandidate.unavailableReasonCode,
        fallbackDetail = providerNativeCandidate.unavailableDetail,
      )
    }
    return SelectionDecoratingSandboxCommandExecutionBackend(
      delegate = delegate,
      selection = selection,
    )
  }

  fun defaultProviderNativeCandidate(): SandboxCommandBackendCandidate =
    SandboxCommandBackendCandidate.unavailable(
      reasonCode = REASON_PROVIDER_NATIVE_TRANSPORT_STACK_MISSING,
      detail = DETAIL_PROVIDER_NATIVE_TRANSPORT_STACK_MISSING,
    )
}

private class SelectionDecoratingSandboxCommandExecutionBackend(
  private val delegate: SandboxCommandExecutionBackend,
  private val selection: SandboxCommandBackendSelection,
) : SandboxCommandExecutionBackend {
  override val capabilities: SandboxCommandBackendCapabilities
    get() = delegate.capabilities

  private val selectionMetadata: Map<String, String> = selection.metadata()
  private val commandExecutor: CommandExecutor by lazy(LazyThreadSafetyMode.NONE) {
    delegate.createCommandExecutor()
  }
  private val managedProcessControllerFactory: ManagedProcessControllerFactory by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    delegate.createManagedProcessControllerFactory()
  }

  override fun createCommandExecutor(): CommandExecutor = object : CommandExecutor() {
    override fun execute(
      request: CommandExecutionRequest,
      policyDecision: PolicyDecision,
      approvalToken: CommandApprovalToken?,
      hooks: RuntimeExecutionHooks,
    ): ExecutionResult {
      val backendTraceMetadata = SandboxExecutionTraceMetadata.backendMetadata(
        metadata = request.metadata,
        backendKind = delegate.capabilities.backendKind,
      )
      val providerTraceMetadata = if (delegate.capabilities.providerNative) {
        SandboxExecutionTraceMetadata.providerStartMetadata(request.metadata + backendTraceMetadata)
      } else {
        emptyMap()
      }
      val result = commandExecutor.execute(
        request = request.copy(
          metadata = request.metadata + selectionMetadata + backendTraceMetadata + providerTraceMetadata,
        ),
        policyDecision = policyDecision,
        approvalToken = approvalToken,
        hooks = hooks,
      )
      return result.copy(
        metadata = result.metadata + selectionMetadata + backendTraceMetadata + providerTraceMetadata,
      )
    }
  }

  override fun createManagedProcessControllerFactory(): ManagedProcessControllerFactory =
    object : ReconnectableManagedProcessControllerFactory {
      override fun start(request: ManagedProcessStartRequest): ManagedProcessController {
        val backendTraceMetadata = SandboxExecutionTraceMetadata.backendMetadata(
          metadata = request.metadata,
          backendKind = delegate.capabilities.backendKind,
        )
        val providerTraceMetadata = if (delegate.capabilities.providerNative) {
          SandboxExecutionTraceMetadata.providerStartMetadata(request.metadata + backendTraceMetadata)
        } else {
          emptyMap()
        }
        val controller = managedProcessControllerFactory.start(
          request.copy(
            metadata = request.metadata + selectionMetadata + backendTraceMetadata + providerTraceMetadata,
          ),
        )
        return decorateController(
          controller = controller,
          decorationMetadata = selectionMetadata + backendTraceMetadata + providerTraceMetadata,
        )
      }

      override fun reconnect(snapshot: ManagedProcessSnapshot): ManagedProcessController? {
        val reconnectableFactory =
          managedProcessControllerFactory as? ReconnectableManagedProcessControllerFactory
          ?: return null
        val reconnectTraceMetadata = SandboxExecutionTraceMetadata.reconnectMetadata(snapshot.metadata)
        val backendTraceMetadata = SandboxExecutionTraceMetadata.backendMetadata(
          metadata = snapshot.metadata + reconnectTraceMetadata,
          backendKind = delegate.capabilities.backendKind,
        )
        val providerTraceMetadata = if (delegate.capabilities.providerNative) {
          SandboxExecutionTraceMetadata.providerConnectMetadata(
            snapshot.metadata + reconnectTraceMetadata + backendTraceMetadata,
          )
        } else {
          emptyMap()
        }
        val controller = reconnectableFactory.reconnect(
          snapshot.copy(
            metadata =
              snapshot.metadata +
                selectionMetadata +
                reconnectTraceMetadata +
                backendTraceMetadata +
                providerTraceMetadata,
          ),
        ) ?: return null
        return decorateController(
          controller = controller,
          decorationMetadata =
            selectionMetadata +
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
