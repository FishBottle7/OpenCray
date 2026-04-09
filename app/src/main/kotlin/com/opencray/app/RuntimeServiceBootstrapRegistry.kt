package com.opencray.app

import android.app.Service
import android.content.Context
import android.os.Handler

internal fun interface RuntimeServiceBootstrapStateProvider {
  fun resolve(
    context: Context,
    serviceLifecycleFactory: () -> RuntimeServiceLifecycleDescriptor,
  ): RuntimeServiceBootstrapState
}

private object BootstrappedRuntimeServiceBootstrapStateProvider :
  RuntimeServiceBootstrapStateProvider {
  override fun resolve(
    context: Context,
    serviceLifecycleFactory: () -> RuntimeServiceLifecycleDescriptor,
  ): RuntimeServiceBootstrapState = createRuntimeServiceBootstrapState(
    appContext = context.applicationContext,
    serviceLifecycle = serviceLifecycleFactory(),
  )
}

internal data class RuntimeServiceBootstrapDependencies(
  val runtimeServiceBootstrapStateProvider: RuntimeServiceBootstrapStateProvider,
  val runtimeServiceGatewayBundleFactory: RuntimeServiceGatewayBundleFactory,
  val runtimeServiceTransportBootstrapFactory: OpenCrayRuntimeServiceTransportBootstrapFactory,
  val runtimeServiceExecutionCoordinatorFactory: RuntimeServiceExecutionCoordinatorFactory,
  val runtimeServiceControllerBundleFactory: RuntimeServiceControllerBundleFactory,
  val runtimeServiceWakeCommandDispatcherFactory: RuntimeServiceWakeCommandDispatcherFactory,
  val runtimeServiceBinderEndpointFactory: RuntimeServiceBinderEndpointFactory,
) {
  fun resolveRuntimeServiceBootstrapState(
    context: Context,
    serviceLifecycleFactory: () -> RuntimeServiceLifecycleDescriptor = {
      RuntimeServiceLifecycleDescriptor()
    }
  ): RuntimeServiceBootstrapState = runtimeServiceBootstrapStateProvider.resolve(
    context = context.applicationContext,
    serviceLifecycleFactory = serviceLifecycleFactory,
  )

  fun resolveRuntimeServiceTransportBootstrap(
    context: Context,
    bootstrapState: RuntimeServiceBootstrapState,
    runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState,
    runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar,
  ): OpenCrayRuntimeServiceTransportBootstrap = runtimeServiceTransportBootstrapFactory.create(
    appContext = context.applicationContext,
    gatewayDependencies = bootstrapState.gatewayDependencies,
    runtimeServiceGatewayBundleFactory = runtimeServiceGatewayBundleFactory,
    runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
    runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
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

  fun resolveRuntimeServiceControllerBundle(
    service: Service,
    context: Context,
    mainHandler: Handler,
  ): RuntimeServiceControllerBundle = runtimeServiceControllerBundleFactory.create(
    service = service,
    appContext = context.applicationContext,
    mainHandler = mainHandler,
  )

  fun resolveRuntimeServiceWakeCommandDispatcher(
    context: Context,
    bootstrapState: RuntimeServiceBootstrapState,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    serviceExecutionCoordinator: RuntimeServiceExecutionCoordinator,
  ): RuntimeServiceWakeCommandDispatcher = runtimeServiceWakeCommandDispatcherFactory.create(
    appContext = context.applicationContext,
    dispatcherDependencies = bootstrapState.wakeCommandDispatcherDependencies,
    gatewayBundle = gatewayBundle,
    serviceExecutionCoordinator = serviceExecutionCoordinator,
  )

  fun resolveRuntimeServiceBinderEndpoint(
    bootstrapState: RuntimeServiceBootstrapState,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    serviceExecutionCoordinator: RuntimeServiceExecutionCoordinator,
  ): RuntimeServiceBinderEndpoint = runtimeServiceBinderEndpointFactory.create(
    binderEndpointDependencies = bootstrapState.binderEndpointDependencies,
    gatewayBundle = gatewayBundle,
    serviceExecutionCoordinator = serviceExecutionCoordinator,
  )
}

internal data class RuntimeServiceBootstrapState(
  val gatewayDependencies: RuntimeServiceGatewayBundleDependencies,
  val executionCoordinatorDependencies: RuntimeServiceExecutionCoordinatorDependencies,
  val wakeCommandDispatcherDependencies: RuntimeServiceWakeCommandDispatcherDependencies,
  val binderEndpointDependencies: RuntimeServiceBinderEndpointDependencies,
)

private fun defaultRuntimeServiceBootstrapDependencies(): RuntimeServiceBootstrapDependencies =
  RuntimeServiceBootstrapDependencies(
    runtimeServiceBootstrapStateProvider = BootstrappedRuntimeServiceBootstrapStateProvider,
    runtimeServiceGatewayBundleFactory = DefaultRuntimeServiceGatewayBundleFactory,
    runtimeServiceTransportBootstrapFactory =
      DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(),
    runtimeServiceExecutionCoordinatorFactory = DefaultRuntimeServiceExecutionCoordinatorFactory,
    runtimeServiceControllerBundleFactory = DefaultRuntimeServiceControllerBundleFactory,
    runtimeServiceWakeCommandDispatcherFactory = DefaultRuntimeServiceWakeCommandDispatcherFactory,
    runtimeServiceBinderEndpointFactory = DefaultRuntimeServiceBinderEndpointFactory,
  )

internal object RuntimeServiceBootstrapRegistry {
  @Volatile
  private var bootstrapDependencies: RuntimeServiceBootstrapDependencies =
    defaultRuntimeServiceBootstrapDependencies()

  internal fun resolveBootstrapDependencies(): RuntimeServiceBootstrapDependencies =
    bootstrapDependencies

  internal fun resolveRuntimeServiceBootstrapState(
    context: Context,
    serviceLifecycleFactory: () -> RuntimeServiceLifecycleDescriptor = {
      RuntimeServiceLifecycleDescriptor()
    },
  ): RuntimeServiceBootstrapState = resolveBootstrapDependencies().resolveRuntimeServiceBootstrapState(
    context = context,
    serviceLifecycleFactory = serviceLifecycleFactory,
  )

  internal fun setRuntimeServiceBootstrapStateProviderForTest(
    provider: RuntimeServiceBootstrapStateProvider?,
  ) {
    bootstrapDependencies = bootstrapDependencies.copy(
      runtimeServiceBootstrapStateProvider =
        provider ?: defaultRuntimeServiceBootstrapDependencies().runtimeServiceBootstrapStateProvider,
    )
  }

  internal fun resolveRuntimeServiceTransportBootstrap(
    context: Context,
    bootstrapState: RuntimeServiceBootstrapState,
    runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState,
    runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar,
  ): OpenCrayRuntimeServiceTransportBootstrap =
    resolveBootstrapDependencies().resolveRuntimeServiceTransportBootstrap(
      context = context,
      bootstrapState = bootstrapState,
      runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
      runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
  )

  internal fun setRuntimeServiceTransportBootstrapFactoryForTest(
    factory: OpenCrayRuntimeServiceTransportBootstrapFactory?,
  ) {
    bootstrapDependencies = bootstrapDependencies.copy(
      runtimeServiceTransportBootstrapFactory =
        factory
          ?: defaultRuntimeServiceBootstrapDependencies().runtimeServiceTransportBootstrapFactory,
    )
  }

  internal fun setRuntimeServiceGatewayBundleFactoryForTest(
    factory: RuntimeServiceGatewayBundleFactory?,
  ) {
    bootstrapDependencies = bootstrapDependencies.copy(
      runtimeServiceGatewayBundleFactory =
        factory ?: defaultRuntimeServiceBootstrapDependencies().runtimeServiceGatewayBundleFactory,
    )
  }

  internal fun resolveRuntimeServiceExecutionCoordinator(
    context: Context,
    bootstrapState: RuntimeServiceBootstrapState,
    keepAliveController: RuntimeServiceKeepAliveController,
    runtimeForegroundController: RuntimeForegroundController,
  ): RuntimeServiceExecutionCoordinator =
    resolveBootstrapDependencies().resolveRuntimeServiceExecutionCoordinator(
      context = context,
      bootstrapState = bootstrapState,
      keepAliveController = keepAliveController,
      runtimeForegroundController = runtimeForegroundController,
  )

  internal fun setRuntimeServiceExecutionCoordinatorFactoryForTest(
    factory: RuntimeServiceExecutionCoordinatorFactory?,
  ) {
    bootstrapDependencies = bootstrapDependencies.copy(
      runtimeServiceExecutionCoordinatorFactory =
        factory
          ?: defaultRuntimeServiceBootstrapDependencies().runtimeServiceExecutionCoordinatorFactory,
    )
  }

  internal fun resolveRuntimeServiceControllerBundle(
    service: Service,
    context: Context,
    mainHandler: Handler,
  ): RuntimeServiceControllerBundle =
    resolveBootstrapDependencies().resolveRuntimeServiceControllerBundle(
      service = service,
      context = context,
      mainHandler = mainHandler,
    )

  internal fun setRuntimeServiceControllerBundleFactoryForTest(
    factory: RuntimeServiceControllerBundleFactory?,
  ) {
    bootstrapDependencies = bootstrapDependencies.copy(
      runtimeServiceControllerBundleFactory =
        factory ?: defaultRuntimeServiceBootstrapDependencies().runtimeServiceControllerBundleFactory,
    )
  }

  internal fun resolveRuntimeServiceWakeCommandDispatcher(
    context: Context,
    bootstrapState: RuntimeServiceBootstrapState,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    serviceExecutionCoordinator: RuntimeServiceExecutionCoordinator,
  ): RuntimeServiceWakeCommandDispatcher =
    resolveBootstrapDependencies().resolveRuntimeServiceWakeCommandDispatcher(
      context = context,
      bootstrapState = bootstrapState,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
    )

  internal fun setRuntimeServiceWakeCommandDispatcherFactoryForTest(
    factory: RuntimeServiceWakeCommandDispatcherFactory?,
  ) {
    bootstrapDependencies = bootstrapDependencies.copy(
      runtimeServiceWakeCommandDispatcherFactory =
        factory
          ?: defaultRuntimeServiceBootstrapDependencies().runtimeServiceWakeCommandDispatcherFactory,
    )
  }

  internal fun resolveRuntimeServiceBinderEndpoint(
    bootstrapState: RuntimeServiceBootstrapState,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    serviceExecutionCoordinator: RuntimeServiceExecutionCoordinator,
  ): RuntimeServiceBinderEndpoint =
    resolveBootstrapDependencies().resolveRuntimeServiceBinderEndpoint(
      bootstrapState = bootstrapState,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
    )

  internal fun setRuntimeServiceBinderEndpointFactoryForTest(
    factory: RuntimeServiceBinderEndpointFactory?,
  ) {
    bootstrapDependencies = bootstrapDependencies.copy(
      runtimeServiceBinderEndpointFactory =
        factory ?: defaultRuntimeServiceBootstrapDependencies().runtimeServiceBinderEndpointFactory,
    )
  }

  internal fun setBootstrapDependenciesForTest(
    dependencies: RuntimeServiceBootstrapDependencies?,
  ) {
    bootstrapDependencies = dependencies ?: defaultRuntimeServiceBootstrapDependencies()
  }

  internal fun clearForTest() {
    bootstrapDependencies = defaultRuntimeServiceBootstrapDependencies()
  }
}
