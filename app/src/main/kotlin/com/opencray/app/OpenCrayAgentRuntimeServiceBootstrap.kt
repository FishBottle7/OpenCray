package com.opencray.app

import android.app.Service
import android.content.Context
import android.os.Handler

internal data class OpenCrayAgentRuntimeServiceBootstrap(
  val controllerBundle: RuntimeServiceControllerBundle,
  val transportBootstrap: OpenCrayRuntimeServiceTransportBootstrap,
  val executionCoordinator: RuntimeServiceExecutionCoordinator,
  val wakeCommandDispatcher: RuntimeServiceWakeCommandDispatcher,
  val binderEndpoint: RuntimeServiceBinderEndpoint,
)

internal fun openCrayAgentRuntimeServiceBootstrap(
  service: Service,
  appContext: Context,
  mainHandler: Handler,
  bootstrapDependencies: RuntimeServiceBootstrapDependencies =
    RuntimeServiceBootstrapRegistry.resolveBootstrapDependencies(),
): OpenCrayAgentRuntimeServiceBootstrap {
  val controllerBundle = bootstrapDependencies.resolveRuntimeServiceControllerBundle(
    service = service,
    context = appContext,
    mainHandler = mainHandler,
  )
  val bootstrapState = bootstrapDependencies.resolveRuntimeServiceBootstrapState(
    context = appContext,
    serviceLifecycleFactory = { RuntimeServiceLifecycleDescriptor() },
  )
  val transportBootstrap = bootstrapDependencies.resolveRuntimeServiceTransportBootstrap(
    context = appContext,
    bootstrapState = bootstrapState,
    runtimeServiceKeepAliveStateProvider = controllerBundle.keepAliveController::currentState,
    runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
      controllerBundle.keepAliveController.observe { listener() }
    },
  )
  val executionCoordinator = bootstrapDependencies.resolveRuntimeServiceExecutionCoordinator(
    context = appContext,
    bootstrapState = bootstrapState,
    keepAliveController = controllerBundle.keepAliveController,
    runtimeForegroundController = controllerBundle.runtimeForegroundController,
  )
  val wakeCommandDispatcher = bootstrapDependencies.resolveRuntimeServiceWakeCommandDispatcher(
    context = appContext,
    bootstrapState = bootstrapState,
    gatewayBundle = transportBootstrap.gatewayBundle,
    serviceExecutionCoordinator = executionCoordinator,
  )
  val binderEndpoint = bootstrapDependencies.resolveRuntimeServiceBinderEndpoint(
    bootstrapState = bootstrapState,
    gatewayBundle = transportBootstrap.gatewayBundle,
    serviceExecutionCoordinator = executionCoordinator,
  )
  return OpenCrayAgentRuntimeServiceBootstrap(
    controllerBundle = controllerBundle,
    transportBootstrap = transportBootstrap,
    executionCoordinator = executionCoordinator,
    wakeCommandDispatcher = wakeCommandDispatcher,
    binderEndpoint = binderEndpoint,
  )
}
