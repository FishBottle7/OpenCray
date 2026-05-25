package com.opencray.app

import android.app.Service
import android.content.Context
import android.os.Handler

internal data class RuntimeServiceResolvedBootstrap(
  val bootstrapState: RuntimeServiceBootstrapState,
  private val resetRuntimeOwnerAction: () -> Unit = {},
) {
  fun resetRuntimeOwner() {
    resetRuntimeOwnerAction()
  }
}

internal fun interface RuntimeServiceBootstrapStateProvider {
  fun resolve(
    context: Context,
    target: RuntimeServiceTarget,
    serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  ): RuntimeServiceResolvedBootstrap
}

internal data class RuntimeServiceBootstrapDependencies(
  val runtimeServiceBootstrapStateProvider: RuntimeServiceBootstrapStateProvider,
  val localHostGatewayProvider: (Context) -> OpenCrayLocalHostGateway,
  val runtimeServiceGatewayBundleFactory: RuntimeServiceGatewayBundleFactory,
  val runtimeServiceTransportBootstrapFactory: OpenCrayRuntimeServiceTransportBootstrapFactory,
  val runtimeServiceExecutionCoordinatorFactory: RuntimeServiceExecutionCoordinatorFactory,
  val runtimeServiceShellControlBundleFactory: RuntimeServiceShellControlBundleFactory,
  val runtimeServiceWakeCommandDispatcherFactory: RuntimeServiceWakeCommandDispatcherFactory,
  val runtimeServiceBinderEndpointFactory: RuntimeServiceBinderEndpointFactory,
) {
  fun resolveRuntimeServiceBootstrap(
    context: Context,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
    serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  ): RuntimeServiceResolvedBootstrap = runtimeServiceBootstrapStateProvider.resolve(
    context = context.applicationContext,
    target = target,
    serviceLifecycle = serviceLifecycle,
  )

  fun resolveRuntimeServiceTransportBootstrap(
    context: Context,
    target: RuntimeServiceTarget,
    bootstrapState: RuntimeServiceBootstrapState,
    runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState,
    runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar,
  ): OpenCrayRuntimeServiceTransportBootstrap = runtimeServiceTransportBootstrapFactory.create(
    appContext = context.applicationContext,
    runtimeTarget = target,
    localGatewayProvider = { localHostGatewayProvider(context.applicationContext) },
    gatewayDependencies = bootstrapState.gatewayDependencies,
    runtimeServiceGatewayBundleFactory = runtimeServiceGatewayBundleFactory,
    runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
    runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
    transportCoordinator = bootstrapState.transportCoordinator,
  )

  fun resolveRuntimeServiceExecutionCoordinator(
    context: Context,
    bootstrapState: RuntimeServiceBootstrapState,
    keepAliveController: RuntimeServiceKeepAliveController,
    runtimeForegroundController: RuntimeForegroundController,
  ): RuntimeServiceExecutionCoordinator = runtimeServiceExecutionCoordinatorFactory.create(
    appContext = context.applicationContext,
    coordinatorDependencies = bootstrapState.executionCoordinatorDependencies,
    keepAliveController = keepAliveController,
    runtimeForegroundController = runtimeForegroundController,
  )

  fun resolveRuntimeServiceShellControlBundle(
    service: Service,
    context: Context,
    mainHandler: Handler,
    bootstrapState: RuntimeServiceBootstrapState,
  ): RuntimeServiceShellControlBundle = runtimeServiceShellControlBundleFactory.create(
    service = service,
    appContext = context.applicationContext,
    mainHandler = mainHandler,
    retainedShellControl = bootstrapState.retainedShellControl,
  )

  fun resolveRuntimeServiceWakeCommandDispatcher(
    context: Context,
    bootstrapState: RuntimeServiceBootstrapState,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
  ): RuntimeServiceWakeCommandDispatcher = runtimeServiceWakeCommandDispatcherFactory.create(
    appContext = context.applicationContext,
    dispatcherDependencies = bootstrapState.wakeCommandDispatcherDependencies,
    gatewayBundle = gatewayBundle,
    projectionCoordinator = bootstrapState.executionCoordinatorDependencies.projectionCoordinator,
  )

  fun resolveRuntimeServiceBinderEndpoint(
    bootstrapState: RuntimeServiceBootstrapState,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    shellStateAccess: RuntimeServiceShellStateAccess,
  ): RuntimeServiceBinderEndpoint = runtimeServiceBinderEndpointFactory.create(
    binderEndpointDependencies = bootstrapState.binderEndpointDependencies,
    gatewayBundle = gatewayBundle,
    shellStateAccess = shellStateAccess,
    projectionCoordinator = bootstrapState.executionCoordinatorDependencies.projectionCoordinator,
  )
}

internal data class RuntimeServiceBootstrapState(
  val gatewayDependencies: RuntimeServiceGatewayBundleDependencies,
  val executionCoordinatorDependencies: RuntimeServiceExecutionCoordinatorDependencies,
  val wakeCommandDispatcherDependencies: RuntimeServiceWakeCommandDispatcherDependencies,
  val binderEndpointDependencies: RuntimeServiceBinderEndpointDependencies,
  val retainedShellControl: RuntimeServiceRetainedShellControl,
  val transportCoordinator: RuntimeServiceTransportCoordinator,
)

internal fun defaultRuntimeServiceBootstrapDependencies(
  runtimeEnvironment: OpenCrayRuntimeServiceEnvironment,
  executionControllerResolver: RuntimeServiceExecutionControllerResolver =
    runtimeEnvironment.executionControllerResolver,
): RuntimeServiceBootstrapDependencies =
  RuntimeServiceBootstrapDependencies(
    runtimeServiceBootstrapStateProvider = RuntimeServiceBootstrapStateProvider {
        context,
        target,
        serviceLifecycle,
      ->
      val executionController = executionControllerResolver.resolve(
        context = context,
        target = target,
      )
      RuntimeServiceResolvedBootstrap(
        bootstrapState = executionController.toRuntimeServiceBootstrapState(serviceLifecycle),
        resetRuntimeOwnerAction = {
          executionController.replaceRuntimeOwner()
        },
      )
    },
    localHostGatewayProvider = { context ->
      runtimeEnvironment.localHostGateway(context.applicationContext)
    },
    runtimeServiceGatewayBundleFactory = DefaultRuntimeServiceGatewayBundleFactory,
    runtimeServiceTransportBootstrapFactory =
      DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(),
    runtimeServiceExecutionCoordinatorFactory = DefaultRuntimeServiceExecutionCoordinatorFactory,
    runtimeServiceShellControlBundleFactory = DefaultRuntimeServiceShellControlBundleFactory(),
    runtimeServiceWakeCommandDispatcherFactory = DefaultRuntimeServiceWakeCommandDispatcherFactory,
    runtimeServiceBinderEndpointFactory = DefaultRuntimeServiceBinderEndpointFactory,
  )
