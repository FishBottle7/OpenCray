package com.opencray.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler

internal interface RuntimeServiceShellAttachment : RuntimeServiceShellStateAccess {
  val binderEndpoint: RuntimeServiceBinderEndpoint

  fun resetRuntimeOwner()

  fun attach(): RuntimeServiceShellAttachResult

  fun startBootstrapForeground()

  fun onStartCommand(
    intent: Intent?,
    startId: Int,
  )

  fun dispose()
}

internal sealed interface RuntimeServiceShellAttachResult {
  data object Attached : RuntimeServiceShellAttachResult

  data object OwnerLeaseDenied : RuntimeServiceShellAttachResult

  data object TransportStartFailed : RuntimeServiceShellAttachResult
}

internal data class OpenCrayAgentRuntimeServiceBootstrap(
  private val resetRuntimeOwnerAction: () -> Unit = {},
  val shellControlBundle: RuntimeServiceShellControlBundle,
  val transportBootstrap: OpenCrayRuntimeServiceTransportBootstrap,
  val executionCoordinator: RuntimeServiceExecutionCoordinator,
  val wakeCommandDispatcher: RuntimeServiceWakeCommandDispatcher,
  override val binderEndpoint: RuntimeServiceBinderEndpoint,
  val projectionCoordinator: RuntimeServiceProjectionCoordinator =
    NoOpRuntimeServiceProjectionCoordinator,
) : RuntimeServiceShellAttachment {
  override fun resetRuntimeOwner() {
    resetRuntimeOwnerAction()
  }

  override fun attach(): RuntimeServiceShellAttachResult {
    if (!projectionCoordinator.tryAcquireOwnerLease()) {
      return RuntimeServiceShellAttachResult.OwnerLeaseDenied
    }
    if (!transportBootstrap.ensureStarted()) {
      projectionCoordinator.persistProjectionSnapshot()
      return RuntimeServiceShellAttachResult.TransportStartFailed
    }
    shellControlBundle.attach()
    executionCoordinator.attach()
    projectionCoordinator.start()
    return RuntimeServiceShellAttachResult.Attached
  }

  override fun startBootstrapForeground() {
    shellControlBundle.runtimeForegroundController.startBootstrapForeground()
  }

  override fun onStartCommand(
    intent: Intent?,
    startId: Int,
  ) {
    if (!projectionCoordinator.tryAcquireOwnerLease()) {
      return
    }
    executionCoordinator.onStartCommand(startId)
    wakeCommandDispatcher.dispatch(intent)
  }

  override fun currentKeepAliveState(): RuntimeServiceKeepAliveState =
    executionCoordinator.currentKeepAliveState()

  override fun currentForegroundState(): RuntimeForegroundState =
    executionCoordinator.currentForegroundState()

  override fun ownsRuntimeServiceStartResult(): Boolean =
    projectionCoordinator.tryAcquireOwnerLease()

  override fun dispose() {
    shellControlBundle.dispose.invoke()
    transportBootstrap.dispose.invoke()
    executionCoordinator.dispose()
  }
}

internal fun openCrayAgentRuntimeServiceBootstrap(
  service: Service,
  appContext: Context,
  mainHandler: Handler,
  target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  bootstrapDependencies: RuntimeServiceBootstrapDependencies,
  serviceProcessDescriptorProvider: (
    Context,
    RuntimeServiceTarget,
  ) -> RuntimeServiceProcessDescriptor = { context, resolvedTarget ->
    runtimeServiceProcessDescriptorForContext(
      context = context,
      expectedProcessSuffix = runtimeServiceProcessSuffixForTarget(resolvedTarget),
    )
  },
): OpenCrayAgentRuntimeServiceBootstrap {
  val serviceLifecycle = RuntimeServiceLifecycleDescriptor(
    serviceProcess = requireDedicatedRuntimeServiceProcess(
      serviceProcessDescriptorProvider(appContext, target),
    ),
  )
  val resolvedBootstrap = bootstrapDependencies.resolveRuntimeServiceBootstrap(
    context = appContext,
    target = target,
    serviceLifecycle = serviceLifecycle,
  )
  val bootstrapState = resolvedBootstrap.bootstrapState
  val shellControlBundle = bootstrapDependencies.resolveRuntimeServiceShellControlBundle(
    service = service,
    context = appContext,
    mainHandler = mainHandler,
    target = target,
    bootstrapState = bootstrapState,
  )
  val transportBootstrap = bootstrapDependencies.resolveRuntimeServiceTransportBootstrap(
    context = appContext,
    target = target,
    bootstrapState = bootstrapState,
    runtimeServiceKeepAliveStateProvider = shellControlBundle.keepAliveController::currentState,
    runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
      shellControlBundle.keepAliveController.observe { listener() }
    },
  )
  val executionCoordinator = bootstrapDependencies.resolveRuntimeServiceExecutionCoordinator(
    context = appContext,
    bootstrapState = bootstrapState,
    keepAliveController = shellControlBundle.keepAliveController,
    runtimeForegroundController = shellControlBundle.runtimeForegroundController,
  )
  val wakeCommandDispatcher = bootstrapDependencies.resolveRuntimeServiceWakeCommandDispatcher(
    context = appContext,
    bootstrapState = bootstrapState,
    gatewayBundle = transportBootstrap.gatewayBundle,
  )
  val binderEndpoint = bootstrapDependencies.resolveRuntimeServiceBinderEndpoint(
    bootstrapState = bootstrapState,
    gatewayBundle = transportBootstrap.gatewayBundle,
    shellStateAccess = executionCoordinator,
  )
  return OpenCrayAgentRuntimeServiceBootstrap(
    resetRuntimeOwnerAction = resolvedBootstrap::resetRuntimeOwner,
    shellControlBundle = shellControlBundle,
    transportBootstrap = transportBootstrap,
    executionCoordinator = executionCoordinator,
    wakeCommandDispatcher = wakeCommandDispatcher,
    binderEndpoint = binderEndpoint,
    projectionCoordinator = bootstrapState.executionCoordinatorDependencies.projectionCoordinator,
  )
}
