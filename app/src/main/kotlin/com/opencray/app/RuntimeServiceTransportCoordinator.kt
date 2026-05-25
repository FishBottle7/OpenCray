package com.opencray.app

internal interface RuntimeServiceTransportCoordinator {
  fun bindGatewayBundle(gatewayBundle: OpenCrayRuntimeServiceGatewayBundle)

  fun releaseGatewayBundle(gatewayBundle: OpenCrayRuntimeServiceGatewayBundle)

  fun currentGatewayBundle(): OpenCrayRuntimeServiceGatewayBundle

  fun bindLocalRuntimeServerStateProvider(provider: () -> LocalRuntimeServerState?)

  fun currentLocalRuntimeServerState(): LocalRuntimeServerState?

  fun dispose()
}

internal class DefaultRuntimeServiceTransportCoordinator(
  private val runtimeTarget: RuntimeServiceTarget = RuntimeServiceTarget.INTERACTIVE,
  initialLocalRuntimeServerStateProvider: (() -> LocalRuntimeServerState?)? = null,
) : RuntimeServiceTransportCoordinator {
  private val lock = Any()
  private val defaultLocalRuntimeServerStateProvider: () -> LocalRuntimeServerState? = {
    defaultLocalRuntimeServerState(runtimeTarget)
  }
  private var gatewayBundle: OpenCrayRuntimeServiceGatewayBundle? = null
  private var localRuntimeServerStateProvider: () -> LocalRuntimeServerState? =
    initialLocalRuntimeServerStateProvider ?: defaultLocalRuntimeServerStateProvider

  override fun bindGatewayBundle(gatewayBundle: OpenCrayRuntimeServiceGatewayBundle) {
    val previousBundle = synchronized(lock) {
      val currentBundle = this.gatewayBundle
      if (currentBundle === gatewayBundle) {
        return@synchronized null
      }
      this.gatewayBundle = gatewayBundle
      currentBundle
    }
    previousBundle?.dispose()
  }

  override fun releaseGatewayBundle(gatewayBundle: OpenCrayRuntimeServiceGatewayBundle) {
    val releasedBundle = synchronized(lock) {
      val currentBundle = this.gatewayBundle
      if (currentBundle !== gatewayBundle) {
        return@synchronized null
      }
      this.gatewayBundle = null
      currentBundle
    }
    releasedBundle?.dispose()
  }

  override fun bindLocalRuntimeServerStateProvider(provider: () -> LocalRuntimeServerState?) {
    synchronized(lock) {
      localRuntimeServerStateProvider = provider
    }
  }

  override fun currentGatewayBundle(): OpenCrayRuntimeServiceGatewayBundle =
    synchronized(lock) {
      gatewayBundle
    } ?: error("Runtime service transport coordinator accessed before a gateway bundle was bound.")

  override fun currentLocalRuntimeServerState(): LocalRuntimeServerState? =
    synchronized(lock) {
      localRuntimeServerStateProvider
    }.invoke() ?: defaultLocalRuntimeServerStateProvider()

  override fun dispose() {
    val currentBundle = synchronized(lock) {
      localRuntimeServerStateProvider = defaultLocalRuntimeServerStateProvider
      gatewayBundle.also {
        gatewayBundle = null
      }
    }
    currentBundle?.dispose()
  }
}
