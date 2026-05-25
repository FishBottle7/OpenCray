package com.opencray.app

import android.content.Context

internal data class OpenCrayServiceBackedGatewayBundle(
  val shellGateway: OpenCrayShellGateway,
  val chatRuntimeGateway: OpenCrayChatRuntimeGateway,
  val skillsGateway: OpenCraySkillsGateway,
  val settingsGateway: OpenCraySettingsGateway,
)

internal val DEFAULT_CLIENT_RUNTIME_SERVICE_TARGET: RuntimeServiceTarget =
  RuntimeServiceTarget.INTERACTIVE

internal fun interface OpenCrayServiceBackedGatewayBundleFactory {
  fun create(
    context: Context,
    target: RuntimeServiceTarget,
  ): OpenCrayServiceBackedGatewayBundle
}

internal class ConfigurableOpenCrayServiceBackedGatewayBundleFactory(
  private val runtimeServiceClientProvider:
    (Context, RuntimeServiceTarget) -> OpenCrayRuntimeServiceClient,
  private val projectionGatewayBundleFactory: OpenCrayProjectionGatewayBundleFactory,
) :
  OpenCrayServiceBackedGatewayBundleFactory {
  override fun create(
    context: Context,
    target: RuntimeServiceTarget,
  ): OpenCrayServiceBackedGatewayBundle {
    val appContext = context.applicationContext
    val defaultTarget = target
    val serviceClient = runtimeServiceClientProvider(appContext, defaultTarget)
    val projectionGatewayBundleProvider = cachedGatewayProvider {
      projectionGatewayBundleFactory.create(
        context = appContext,
        serviceClient = serviceClient,
      )
    }
    return OpenCrayServiceBackedGatewayBundle(
      shellGateway = serviceBackedOpenCrayShellGateway(
        serviceClient = serviceClient,
        fallbackGatewayProvider = { projectionGatewayBundleProvider().shellGateway },
      ),
      chatRuntimeGateway = serviceBackedOpenCrayChatRuntimeGateway(
        serviceClient = serviceClient,
        fallbackGatewayProvider = { projectionGatewayBundleProvider().chatRuntimeGateway },
      ),
      skillsGateway = serviceBackedOpenCraySkillsGateway(
        serviceClient = serviceClient,
        fallbackGatewayProvider = { projectionGatewayBundleProvider().skillsGateway },
      ),
      settingsGateway = serviceBackedOpenCraySettingsGateway(
        serviceClient = serviceClient,
        fallbackGatewayProvider = { projectionGatewayBundleProvider().settingsGateway },
      ),
    )
  }
}
