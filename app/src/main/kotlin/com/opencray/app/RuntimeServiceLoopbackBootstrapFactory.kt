package com.opencray.app

import android.content.Context

internal data class RuntimeServiceLoopbackBootstrap(
  val ensureStarted: () -> Unit = {},
)

internal fun interface RuntimeServiceLoopbackBootstrapFactory {
  fun create(
    appContext: Context,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
  ): RuntimeServiceLoopbackBootstrap
}

internal class DefaultRuntimeServiceLoopbackBootstrapFactory(
  private val localGatewayProviderFactory: (Context) -> (() -> OpenCrayLocalHostGateway) = { context ->
    { openCrayLocalHostGateway(context) }
  },
  private val ensureServerStarted: (Context, OpenCrayLocalRuntimeServerProviders) -> Unit =
    { context, providers ->
      OpenCrayLocalRuntimeServerRegistry.ensureStarted(
        context = context,
        providers = providers,
      )
    },
) : RuntimeServiceLoopbackBootstrapFactory {
  override fun create(
    appContext: Context,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
  ): RuntimeServiceLoopbackBootstrap {
    val localGatewayProvider = localGatewayProviderFactory(appContext)
    return RuntimeServiceLoopbackBootstrap(
      ensureStarted = {
        ensureServerStarted(
          appContext,
          openCrayLocalRuntimeServerProviders(
            localGatewayProvider = localGatewayProvider,
            gatewayBundle = gatewayBundle,
          ),
        )
      },
    )
  }
}
