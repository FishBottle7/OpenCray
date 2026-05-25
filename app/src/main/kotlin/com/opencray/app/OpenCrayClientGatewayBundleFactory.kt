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
  fun create(
    context: Context,
    target: RuntimeServiceTarget,
  ): OpenCrayClientGatewayBundle
}

internal class ConfigurableOpenCrayClientGatewayBundleFactory(
  private val localHostGatewayProvider: (Context) -> OpenCrayLocalHostGateway,
  private val serviceBackedGatewayBundleFactory: OpenCrayServiceBackedGatewayBundleFactory,
) : OpenCrayClientGatewayBundleFactory {
  private val lock = Any()
  private var bundlesByTarget: Map<RuntimeServiceTarget, OpenCrayClientGatewayBundle> = emptyMap()
  @Volatile
  private var localHostGateway: OpenCrayLocalHostGateway? = null

  override fun create(
    context: Context,
    target: RuntimeServiceTarget,
  ): OpenCrayClientGatewayBundle {
    val appContext = context.applicationContext
    bundlesByTarget[target]?.let { existing ->
      return existing
    }
    return synchronized(lock) {
      bundlesByTarget[target] ?: createBundle(
        appContext = appContext,
        target = target,
      ).also { created ->
        bundlesByTarget = bundlesByTarget + (target to created)
      }
    }
  }

  private fun createBundle(
    appContext: Context,
    target: RuntimeServiceTarget,
  ): OpenCrayClientGatewayBundle {
    val serviceBackedGatewayBundle = serviceBackedGatewayBundleFactory.create(
      appContext,
      target = target,
    )
    return OpenCrayClientGatewayBundle(
      localHostGateway = resolveLocalHostGateway(appContext),
      shellGateway = serviceBackedGatewayBundle.shellGateway,
      chatRuntimeGateway = serviceBackedGatewayBundle.chatRuntimeGateway,
      skillsGateway = serviceBackedGatewayBundle.skillsGateway,
      settingsGateway = serviceBackedGatewayBundle.settingsGateway,
    )
  }

  private fun resolveLocalHostGateway(appContext: Context): OpenCrayLocalHostGateway {
    localHostGateway?.let { existing ->
      return existing
    }
    return synchronized(lock) {
      localHostGateway ?: localHostGatewayProvider(appContext).also { created ->
        localHostGateway = created
      }
    }
  }
}
