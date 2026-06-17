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
    val loopbackBootstrap = loopbackBootstrapFactory.create(
      appContext = appContext,
      runtimeTarget = runtimeTarget,
      localGatewayProvider = localGatewayProvider,
      gatewayBundle = gatewayBundle,
      transportCoordinator = transportCoordinator,
    )
    val lock = Any()
    var starting = false
    var activated = false
    var disposed = false
    return OpenCrayRuntimeServiceTransportBootstrap(
      gatewayBundle = gatewayBundle,
      ensureStarted = ensureStarted@{
        val shouldStart = synchronized(lock) {
          if (disposed) {
            return@ensureStarted
          }
          if (starting || activated) {
            false
          } else {
            starting = true
            true
          }
        }
        if (!shouldStart) {
          return@ensureStarted
        }
        try {
          val started = loopbackBootstrap.ensureStarted()
          synchronized(lock) {
            starting = false
            if (disposed) {
              return@ensureStarted
            }
            if (!started) {
              return@ensureStarted
            }
            activated = true
          }
          transportCoordinator.bindGatewayBundle(gatewayBundle)
        } catch (throwable: Throwable) {
          synchronized(lock) {
            starting = false
          }
          throw throwable
        }
      },
      dispose = dispose@{
        val releaseFromCoordinator = synchronized(lock) {
          if (disposed) {
            return@synchronized null
          }
          disposed = true
          activated
        } ?: return@dispose
        loopbackBootstrap.dispose()
        if (releaseFromCoordinator) {
          transportCoordinator.releaseGatewayBundle(gatewayBundle)
        } else {
          gatewayBundle.dispose()
        }
      },
    )
  }
}
