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

  override fun saveShellDestination(
    selectedTab: String,
    settingsSubpage: String?,
  ) {
    currentGateway().saveShellDestination(
      selectedTab = selectedTab,
      settingsSubpage = settingsSubpage,
    )
  }

  private fun currentGateway(): OpenCrayShellGateway =
    serviceClient.peekShellGateway() ?: fallbackGateway
}

internal fun serviceBackedOpenCrayShellGateway(
  context: Context,
): OpenCrayShellGateway {
  val appContext = context.applicationContext
  val serviceClient = OpenCrayRuntimeServiceAccess.ensureClient(appContext)
  return serviceBackedOpenCrayShellGateway(
    serviceClient = serviceClient,
    fallbackGateway = projectionOnlyOpenCrayShellGateway(
      context = appContext,
      serviceClient = serviceClient,
    ),
  )
}

internal fun serviceBackedOpenCrayShellGateway(
  serviceClient: OpenCrayRuntimeServiceClient,
  fallbackGateway: OpenCrayShellGateway,
): OpenCrayShellGateway = ServiceBackedOpenCrayShellGateway(
  serviceClient = serviceClient,
  fallbackGateway = fallbackGateway,
)

internal fun serviceBackedOpenCrayShellGateway(
  context: Context,
  fallbackGateway: OpenCrayShellGateway,
): OpenCrayShellGateway = serviceBackedOpenCrayShellGateway(
  serviceClient = OpenCrayRuntimeServiceAccess.ensureClient(context.applicationContext),
  fallbackGateway = fallbackGateway,
)
