package com.opencray.app

import android.content.Context

internal class ServiceBackedOpenCrayShellGateway(
  private val serviceClient: OpenCrayRuntimeServiceClient,
  private val fallbackGateway: OpenCrayShellGateway,
) : OpenCrayShellGateway {
  override fun loadShellSnapshot(): Map<String, Any?> =
    currentGateway().loadShellSnapshot()

  override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithDynamicGateway(
      currentGateway = ::currentGateway,
      observeConnectionState = serviceClient::observePassiveConnectionState,
      observe = { gateway, callback -> gateway.observeShell(callback) },
      listener = listener,
    )

  private fun currentGateway(): OpenCrayShellGateway =
    serviceClient.peekShellGateway() ?: fallbackGateway
}

internal fun serviceBackedOpenCrayShellGateway(
  context: Context,
): OpenCrayShellGateway {
  val appContext = context.applicationContext
  val serviceClient = OpenCrayAgentRuntimeService.ensureClient(appContext)
  return ServiceBackedOpenCrayShellGateway(
    serviceClient = serviceClient,
    fallbackGateway = projectionOnlyOpenCrayShellGateway(
      context = appContext,
      serviceClient = serviceClient,
    ),
  )
}

internal fun serviceBackedOpenCrayShellGateway(
  context: Context,
  fallbackGateway: OpenCrayShellGateway,
): OpenCrayShellGateway = ServiceBackedOpenCrayShellGateway(
  serviceClient = OpenCrayAgentRuntimeService.ensureClient(context.applicationContext),
  fallbackGateway = fallbackGateway,
)
