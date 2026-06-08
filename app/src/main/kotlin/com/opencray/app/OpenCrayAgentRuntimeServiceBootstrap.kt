package com.opencray.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler

internal interface RuntimeServiceShellAttachment : RuntimeServiceShellStateAccess {
  val binderEndpoint: RuntimeServiceBinderEndpoint

  fun resetRuntimeOwner()

  fun attach()

  fun startBootstrapForeground()

  fun onStartCommand(
    intent: Intent?,
    startId: Int,
  )

  fun dispose()
}

internal data class OpenCrayAgentRuntimeServiceBootstrap(
  private val resetRuntimeOwnerAction: () -> Unit = {},
  val shellControlBundle: RuntimeServiceShellControlBundle,
  val transportBootstrap: OpenCrayRuntimeServiceTransportBootstrap,
  val executionCoordinator: RuntimeServiceExecutionCoordinator,
  val wakeCommandDispatcher: RuntimeServiceWakeCommandDispatcher,
  override val binderEndpoint: RuntimeServiceBinderEndpoint,
) : RuntimeServiceShellAttachment {
  override fun resetRuntimeOwner() {
    resetRuntimeOwnerAction()
  }

  override fun attach() {
    transportBootstrap.ensureStarted()
    shellControlBundle.attach()
    executionCoordinator.attach()
  }

  override fun startBootstrapForeground() {
    shellControlBundle.runtimeForegroundController.startBootstrapForeground()
  }

  override fun onStartCommand(
    intent: Intent?,
    startId: Int,
  ) {
    executionCoordinator.onStartCommand(startId)
    wakeCommandDispatcher.dispatch(intent)
  }

  override fun currentKeepAliveState(): RuntimeServiceKeepAliveState =
    executionCoordinator.currentKeepAliveState()

  override fun currentForegroundState(): RuntimeForegroundState =
    executionCoordinator.currentForegroundState()

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
): OpenCrayAgentRuntimeServiceBootstrap {
  val serviceLifecycle = RuntimeServiceLifecycleDescriptor(
    serviceProcess = runtimeServiceProcessDescriptorForContext(appContext),
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
  )
}
