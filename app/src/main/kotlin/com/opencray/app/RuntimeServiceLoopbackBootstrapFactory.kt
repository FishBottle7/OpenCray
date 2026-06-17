package com.opencray.app

import android.content.Context

internal data class RuntimeServiceLoopbackBootstrap(
  val ensureStarted: () -> Boolean = { true },
  val dispose: () -> Unit = {},
)

internal fun interface RuntimeServiceLoopbackBootstrapFactory {
  fun create(
    appContext: Context,
    runtimeTarget: RuntimeServiceTarget,
    localGatewayProvider: () -> OpenCrayLocalHostGateway,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    transportCoordinator: RuntimeServiceTransportCoordinator,
  ): RuntimeServiceLoopbackBootstrap
}

internal class DefaultRuntimeServiceLoopbackBootstrapFactory(
  private val ensureServerStarted:
    (Context, RuntimeServiceTarget, OpenCrayLocalRuntimeServerProviders) -> OpenCrayLocalRuntimeServer =
    { _, runtimeTarget, providers ->
      OpenCrayLocalRuntimeServer(
        localGatewayProvider = providers.localGatewayProvider,
        shellGatewayProvider = providers.shellGatewayProvider,
        chatRuntimeGatewayProvider = providers.chatRuntimeGatewayProvider,
        skillsGatewayProvider = providers.skillsGatewayProvider,
        settingsGatewayProvider = providers.settingsGatewayProvider,
        requestedPort = localRuntimeLoopbackPortForTarget(runtimeTarget),
        shutdownExecutorOnClose = true,
      ).also(OpenCrayLocalRuntimeServer::ensureStarted)
    },
) : RuntimeServiceLoopbackBootstrapFactory {
  override fun create(
    appContext: Context,
    runtimeTarget: RuntimeServiceTarget,
    localGatewayProvider: () -> OpenCrayLocalHostGateway,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    transportCoordinator: RuntimeServiceTransportCoordinator,
  ): RuntimeServiceLoopbackBootstrap {
    val serverLock = Any()
    var server: OpenCrayLocalRuntimeServer? = null
    return RuntimeServiceLoopbackBootstrap(
      ensureStarted = {
        val startedServer = synchronized(serverLock) {
          server ?: ensureServerStarted(
            appContext,
            runtimeTarget,
            openCrayLocalRuntimeServerProviders(
              localGatewayProvider = localGatewayProvider,
              shellGatewayProvider = { gatewayBundle.shellGateway },
              chatRuntimeGatewayProvider = { gatewayBundle.chatRuntimeGateway },
              skillsGatewayProvider = { gatewayBundle.skillsGateway },
              settingsGatewayProvider = { gatewayBundle.settingsGateway },
            ),
          ).also { created ->
            server = created
          }
        }
        transportCoordinator.bindLocalRuntimeServerStateProvider(startedServer::currentState)
        startedServer.currentState().phase == LocalRuntimeServerState.PHASE_LISTENING
      },
      dispose = {
        val existingServer = synchronized(serverLock) {
          server.also {
            server = null
          }
        }
        existingServer?.close()
      },
    )
  }
}
