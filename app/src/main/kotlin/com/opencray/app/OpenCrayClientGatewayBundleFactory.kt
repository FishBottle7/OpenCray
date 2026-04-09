package com.opencray.app

import android.content.Context

internal data class OpenCrayClientGatewayBundle(
  val localHostGateway: OpenCrayLocalHostGateway,
  val shellGateway: OpenCrayShellGateway,
  val chatRuntimeGateway: OpenCrayChatRuntimeGateway,
  val skillsGateway: OpenCraySkillsGateway,
  val settingsGateway: OpenCraySettingsGateway,
)

internal fun interface OpenCrayClientGatewayBundleFactory {
  fun create(context: Context): OpenCrayClientGatewayBundle
}

internal object DefaultOpenCrayClientGatewayBundleFactory : OpenCrayClientGatewayBundleFactory {
  override fun create(context: Context): OpenCrayClientGatewayBundle {
    val appContext = context.applicationContext
    val serviceBackedGatewayBundle = DefaultOpenCrayServiceBackedGatewayBundleFactory.create(
      appContext,
    )
    return OpenCrayClientGatewayBundle(
      localHostGateway = openCrayLocalHostGateway(appContext),
      shellGateway = serviceBackedGatewayBundle.shellGateway,
      chatRuntimeGateway = serviceBackedGatewayBundle.chatRuntimeGateway,
      skillsGateway = serviceBackedGatewayBundle.skillsGateway,
      settingsGateway = serviceBackedGatewayBundle.settingsGateway,
    )
  }
}
