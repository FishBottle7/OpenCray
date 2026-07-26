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
    runtimeOwnerWriteGuard: () -> Boolean,
  ): RuntimeServiceLoopbackBootstrap
}

internal class DefaultRuntimeServiceLoopbackBootstrapFactory(
  private val ensureServerStarted:
    (Context, RuntimeServiceTarget, OpenCrayLocalRuntimeServerProviders) -> OpenCrayLocalRuntimeServer =
    { _, _, providers ->
      OpenCrayLocalRuntimeServer(
        localGatewayProvider = providers.localGatewayProvider,
        shellGatewayProvider = providers.shellGatewayProvider,
        chatRuntimeGatewayProvider = providers.chatRuntimeGatewayProvider,
        skillsGatewayProvider = providers.skillsGatewayProvider,
        settingsGatewayProvider = providers.settingsGatewayProvider,
        requestedPort = 0,
        shutdownExecutorOnClose = true,
        runtimeOwnerWriteGuard = providers.runtimeOwnerWriteGuard,
        loopbackSecurity = providers.loopbackSecurity,
      ).also(OpenCrayLocalRuntimeServer::ensureStarted)
    },
  private val descriptorStoreFactory: (Context) -> RuntimeServiceLoopbackDescriptorStore =
    RuntimeServiceLoopbackDescriptorStore::fromContext,
) : RuntimeServiceLoopbackBootstrapFactory {
  override fun create(
    appContext: Context,
    runtimeTarget: RuntimeServiceTarget,
    localGatewayProvider: () -> OpenCrayLocalHostGateway,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    transportCoordinator: RuntimeServiceTransportCoordinator,
    runtimeOwnerWriteGuard: () -> Boolean,
  ): RuntimeServiceLoopbackBootstrap {
    val serverLock = Any()
    var server: OpenCrayLocalRuntimeServer? = null
    var descriptorStore: RuntimeServiceLoopbackDescriptorStore? = null
    var activeDescriptor: RuntimeServiceLoopbackDescriptor? = null
    var disposed = false
    return RuntimeServiceLoopbackBootstrap(
      ensureStarted = {
        val startedServer = synchronized(serverLock) {
          if (disposed) {
            return@synchronized null
          }
          server ?: run {
            val resolvedDescriptorStore = descriptorStoreFactory(appContext)
            val credentials = RuntimeServiceLoopbackCredentials.create()
            val created = ensureServerStarted(
              appContext,
              runtimeTarget,
              OpenCrayLocalRuntimeServerProviders(
                localGatewayProvider = localGatewayProvider,
                shellGatewayProvider = { gatewayBundle.shellGateway },
                chatRuntimeGatewayProvider = { gatewayBundle.chatRuntimeGateway },
                skillsGatewayProvider = { gatewayBundle.skillsGateway },
                settingsGatewayProvider = { gatewayBundle.settingsGateway },
                runtimeOwnerWriteGuard = runtimeOwnerWriteGuard,
                loopbackSecurity = RuntimeServiceLoopbackServerSecurity(credentials),
              ),
            )
            val state = created.currentState()
            if (state.phase == LocalRuntimeServerState.PHASE_LISTENING) {
              val descriptor = RuntimeServiceLoopbackDescriptor(
                target = runtimeTarget,
                port = requireNotNull(state.listeningPort) {
                  "Listening loopback server did not report a port."
                },
                credentials = credentials,
                publishedAtEpochMs = System.currentTimeMillis(),
              )
              try {
                resolvedDescriptorStore.publish(descriptor)
              } catch (throwable: Throwable) {
                created.close()
                throw throwable
              }
              activeDescriptor = descriptor
            }
            descriptorStore = resolvedDescriptorStore
            server = created
            created
          }
        }
        val serverForBinding = startedServer ?: return@ensureStarted false
        transportCoordinator.bindLocalRuntimeServerStateProvider(serverForBinding::currentState)
        serverForBinding.currentState().phase == LocalRuntimeServerState.PHASE_LISTENING
      },
      dispose = {
        val (existingServer, existingDescriptor, existingDescriptorStore) = synchronized(serverLock) {
          disposed = true
          Triple(server, activeDescriptor, descriptorStore).also {
            server = null
            activeDescriptor = null
            descriptorStore = null
          }
        }
        try {
          if (existingDescriptor != null) {
            existingDescriptorStore?.revoke(
              target = runtimeTarget,
              expectedEpoch = existingDescriptor.credentials.epoch,
            )
          }
        } finally {
          existingServer?.close()
        }
      },
    )
  }
}
