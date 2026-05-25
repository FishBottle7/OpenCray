package com.opencray.app

import android.content.Context

internal data class OpenCrayRuntimeServiceTransportBootstrap(
  val gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
  val ensureStarted: () -> Unit = {},
  val dispose: () -> Unit = {},
)

internal fun interface OpenCrayRuntimeServiceTransportBootstrapFactory {
  fun create(
    appContext: Context,
    runtimeTarget: RuntimeServiceTarget,
    localGatewayProvider: () -> OpenCrayLocalHostGateway,
    gatewayDependencies: RuntimeServiceGatewayBundleDependencies,
    runtimeServiceGatewayBundleFactory: RuntimeServiceGatewayBundleFactory,
    runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState,
    runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar,
    transportCoordinator: RuntimeServiceTransportCoordinator,
  ): OpenCrayRuntimeServiceTransportBootstrap
}

internal class DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(
  private val loopbackBootstrapFactory: RuntimeServiceLoopbackBootstrapFactory =
    DefaultRuntimeServiceLoopbackBootstrapFactory(),
) : OpenCrayRuntimeServiceTransportBootstrapFactory {
  override fun create(
    appContext: Context,
    runtimeTarget: RuntimeServiceTarget,
    localGatewayProvider: () -> OpenCrayLocalHostGateway,
    gatewayDependencies: RuntimeServiceGatewayBundleDependencies,
    runtimeServiceGatewayBundleFactory: RuntimeServiceGatewayBundleFactory,
    runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState,
    runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar,
    transportCoordinator: RuntimeServiceTransportCoordinator,
  ): OpenCrayRuntimeServiceTransportBootstrap {
    val gatewayBundle = runtimeServiceGatewayBundleFactory.create(
      appContext = appContext,
      gatewayDependencies = gatewayDependencies,
      runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
      runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
    )
    transportCoordinator.bindGatewayBundle(gatewayBundle)
    val loopbackBootstrap = loopbackBootstrapFactory.create(
      appContext = appContext,
      runtimeTarget = runtimeTarget,
      localGatewayProvider = localGatewayProvider,
      gatewayBundle = gatewayBundle,
      transportCoordinator = transportCoordinator,
    )
    return OpenCrayRuntimeServiceTransportBootstrap(
      gatewayBundle = gatewayBundle,
      ensureStarted = loopbackBootstrap.ensureStarted,
      dispose = {
        loopbackBootstrap.dispose()
        transportCoordinator.releaseGatewayBundle(gatewayBundle)
      },
    )
  }
}
