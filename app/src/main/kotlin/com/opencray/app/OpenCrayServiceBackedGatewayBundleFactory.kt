package com.opencray.app

import android.content.Context

internal data class OpenCrayServiceBackedGatewayBundle(
  val shellGateway: OpenCrayShellGateway,
  val chatRuntimeGateway: OpenCrayChatRuntimeGateway,
  val skillsGateway: OpenCraySkillsGateway,
  val settingsGateway: OpenCraySettingsGateway,
)

internal fun interface OpenCrayServiceBackedGatewayBundleFactory {
  fun create(context: Context): OpenCrayServiceBackedGatewayBundle
}

internal object DefaultOpenCrayServiceBackedGatewayBundleFactory :
  OpenCrayServiceBackedGatewayBundleFactory {
  override fun create(context: Context): OpenCrayServiceBackedGatewayBundle {
    val appContext = context.applicationContext
    val serviceClient = OpenCrayRuntimeServiceAccess.ensureClient(appContext)
    return OpenCrayServiceBackedGatewayBundle(
      shellGateway = serviceBackedOpenCrayShellGateway(
        serviceClient = serviceClient,
        fallbackGateway = projectionOnlyOpenCrayShellGateway(
          context = appContext,
          serviceClient = serviceClient,
        ),
      ),
      chatRuntimeGateway = serviceBackedOpenCrayChatRuntimeGateway(
        serviceClient = serviceClient,
        fallbackGateway = projectionOnlyOpenCrayChatRuntimeGateway(
          context = appContext,
          connectionStateProvider = serviceClient::loadConnectionState,
          projectionSnapshotProvider = serviceClient::peekProjectionSnapshot,
        ),
      ),
      skillsGateway = serviceBackedOpenCraySkillsGateway(
        serviceClient = serviceClient,
        fallbackGateway = projectionOnlyOpenCraySkillsGateway(
          context = appContext,
          connectionStateProvider = serviceClient::loadConnectionState,
        ),
      ),
      settingsGateway = serviceBackedOpenCraySettingsGateway(
        serviceClient = serviceClient,
        fallbackGateway = projectionOnlyOpenCraySettingsGateway(
          context = appContext,
          connectionStateProvider = serviceClient::loadConnectionState,
        ),
      ),
    )
  }
}
