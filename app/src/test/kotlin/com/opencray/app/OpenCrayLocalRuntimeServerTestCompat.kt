package com.opencray.app

import android.content.Context

internal fun interface OpenCrayLocalRuntimeServerProvidersFactory {
  fun create(context: Context): OpenCrayLocalRuntimeServerProviders
}

internal object DefaultOpenCrayLocalRuntimeServerProvidersFactory :
  OpenCrayLocalRuntimeServerProvidersFactory {
  override fun create(context: Context): OpenCrayLocalRuntimeServerProviders {
    val appContext = context.applicationContext
    val runtimeEnvironment = openCrayRuntimeServiceEnvironment(appContext)
    return openCrayLocalRuntimeServerProviders(
      runtimeEnvironment.clientGatewayBundleFactory.create(
        appContext,
        target = runtimeEnvironment.defaultClientRuntimeServiceTarget,
      ),
    )
  }
}

internal object OpenCrayLocalRuntimeServerRegistry {
  @Volatile
  private var instance: OpenCrayLocalRuntimeServer? = null

  @Volatile
  private var providersFactory: OpenCrayLocalRuntimeServerProvidersFactory =
    DefaultOpenCrayLocalRuntimeServerProvidersFactory

  fun peekState(): LocalRuntimeServerState = synchronized(this) {
    instance?.currentState() ?: defaultLocalRuntimeServerState()
  }

  fun fromContext(
    context: Context,
    providers: OpenCrayLocalRuntimeServerProviders? = null,
  ): OpenCrayLocalRuntimeServer {
    val appContext = context.applicationContext
    return instance ?: synchronized(this) {
      instance ?: run {
        val resolvedProviders = providers ?: providersFactory.create(appContext)
        OpenCrayLocalRuntimeServer(
          localGatewayProvider = resolvedProviders.localGatewayProvider,
          shellGatewayProvider = resolvedProviders.shellGatewayProvider,
          chatRuntimeGatewayProvider = resolvedProviders.chatRuntimeGatewayProvider,
          skillsGatewayProvider = resolvedProviders.skillsGatewayProvider,
          settingsGatewayProvider = resolvedProviders.settingsGatewayProvider,
          bindAddress = java.net.InetAddress.getByName("127.0.0.1"),
        ).also { created ->
          instance = created
        }
      }
    }
  }

  fun ensureStarted(
    context: Context,
    providers: OpenCrayLocalRuntimeServerProviders? = null,
  ): OpenCrayLocalRuntimeServer =
    fromContext(context, providers = providers).also { server -> server.ensureStarted() }

  internal fun setProvidersFactoryForTest(
    factory: OpenCrayLocalRuntimeServerProvidersFactory?,
  ) {
    providersFactory = factory ?: DefaultOpenCrayLocalRuntimeServerProvidersFactory
  }

  internal fun clearForTest() {
    val existing = synchronized(this) {
      instance.also {
        instance = null
        providersFactory = DefaultOpenCrayLocalRuntimeServerProvidersFactory
      }
    }
    existing?.close()
  }
}
