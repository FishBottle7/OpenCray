package com.opencray.app

import android.content.Context

internal data class OpenCrayRuntimeServiceTransportBootstrap(
  val gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
  val ensureStarted: () -> Unit = {},
)

internal fun interface OpenCrayRuntimeServiceTransportBootstrapFactory {
  fun create(
    appContext: Context,
    gatewayDependencies: RuntimeServiceGatewayBundleDependencies,
    runtimeServiceGatewayBundleFactory: RuntimeServiceGatewayBundleFactory,
    runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState,
    runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar,
  ): OpenCrayRuntimeServiceTransportBootstrap
}

internal class DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(
  private val loopbackBootstrapFactory: RuntimeServiceLoopbackBootstrapFactory =
    DefaultRuntimeServiceLoopbackBootstrapFactory(),
) : OpenCrayRuntimeServiceTransportBootstrapFactory {
  override fun create(
    appContext: Context,
    gatewayDependencies: RuntimeServiceGatewayBundleDependencies,
    runtimeServiceGatewayBundleFactory: RuntimeServiceGatewayBundleFactory,
    runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState,
    runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar,
  ): OpenCrayRuntimeServiceTransportBootstrap {
    val gatewayBundle = runtimeServiceGatewayBundleFactory.create(
      appContext = appContext,
      gatewayDependencies = gatewayDependencies,
      runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
      runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
    )
    val loopbackBootstrap = loopbackBootstrapFactory.create(
      appContext = appContext,
      gatewayBundle = gatewayBundle,
    )
    return OpenCrayRuntimeServiceTransportBootstrap(
      gatewayBundle = gatewayBundle,
      ensureStarted = loopbackBootstrap.ensureStarted,
    )
  }
}
