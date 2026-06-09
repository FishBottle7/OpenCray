package com.opencray.app

import android.content.Context

internal class ServiceBackedOpenCrayShellGateway(
  private val serviceClient: OpenCrayRuntimeServiceClient,
  fallbackGateway: OpenCrayShellGateway? = null,
  fallbackGatewayProvider: (() -> OpenCrayShellGateway)? = null,
) : OpenCrayShellGateway {
  private val resolvedFallbackGatewayProvider: () -> OpenCrayShellGateway = cachedGatewayProvider(
    fallbackGatewayProvider ?: fallbackGateway?.let { gateway -> { gateway } }
      ?: error("Service-backed shell gateway requires a fallback gateway."),
  )

  override fun loadShellSnapshot(): Map<String, Any?> =
    currentLoadGateway().loadShellSnapshot()

  override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithDynamicGateway(
      currentGateway = ::currentObservedGateway,
      observeConnectionState = serviceClient::observePassiveConnectionState,
      observe = { gateway, callback -> gateway.observeShell(callback) },
      listener = listener,
    )

  override fun saveShellDestination(
    selectedTab: String,
    settingsSubpage: String?,
  ) {
    currentLoadGateway().saveShellDestination(
      selectedTab = selectedTab,
      settingsSubpage = settingsSubpage,
    )
  }

  private fun currentLoadGateway(): OpenCrayShellGateway =
    serviceClient.loadShellGateway() ?: resolvedFallbackGatewayProvider()

  private fun currentObservedGateway(): OpenCrayShellGateway =
    serviceClient.peekShellGateway() ?: resolvedFallbackGatewayProvider()
}

internal fun serviceBackedOpenCrayShellGateway(
  context: Context,
): OpenCrayShellGateway =
  openCrayRuntimeServiceEnvironment(context)
    .serviceBackedGatewayBundleFactory
    .create(
      context = context.applicationContext,
      target = openCrayRuntimeServiceEnvironment(context).defaultClientRuntimeServiceTarget,
    )
    .shellGateway

internal fun serviceBackedOpenCrayShellGateway(
  serviceClient: OpenCrayRuntimeServiceClient,
  fallbackGateway: OpenCrayShellGateway,
): OpenCrayShellGateway = ServiceBackedOpenCrayShellGateway(
  serviceClient = serviceClient,
  fallbackGateway = fallbackGateway,
)

internal fun serviceBackedOpenCrayShellGateway(
  serviceClient: OpenCrayRuntimeServiceClient,
  fallbackGatewayProvider: () -> OpenCrayShellGateway,
): OpenCrayShellGateway = ServiceBackedOpenCrayShellGateway(
  serviceClient = serviceClient,
  fallbackGatewayProvider = fallbackGatewayProvider,
)

internal fun serviceBackedOpenCrayShellGateway(
  context: Context,
  fallbackGateway: OpenCrayShellGateway,
): OpenCrayShellGateway {
  val appContext = context.applicationContext
  val environment = openCrayRuntimeServiceEnvironment(appContext)
  return serviceBackedOpenCrayShellGateway(
    serviceClient = environment.runtimeServiceAccessGateway.ensureClient(
      context = appContext,
      target = environment.defaultClientRuntimeServiceTarget,
    ),
    fallbackGateway = fallbackGateway,
  )
}
