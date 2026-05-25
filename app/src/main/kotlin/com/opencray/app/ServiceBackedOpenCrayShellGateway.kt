package com.opencray.app

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

  private fun currentLoadGateway(): OpenCrayShellGateway =
    serviceClient.loadShellGateway() ?: resolvedFallbackGatewayProvider()

  private fun currentObservedGateway(): OpenCrayShellGateway =
    serviceClient.peekShellGateway() ?: resolvedFallbackGatewayProvider()
}

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
