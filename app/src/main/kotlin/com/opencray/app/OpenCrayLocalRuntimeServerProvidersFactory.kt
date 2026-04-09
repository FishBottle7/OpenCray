package com.opencray.app

import android.content.Context

internal fun interface OpenCrayLocalRuntimeServerProvidersFactory {
  fun create(context: Context): OpenCrayLocalRuntimeServerProviders
}

internal object DefaultOpenCrayLocalRuntimeServerProvidersFactory :
  OpenCrayLocalRuntimeServerProvidersFactory {
  override fun create(context: Context): OpenCrayLocalRuntimeServerProviders {
    val gatewayBundle = DefaultOpenCrayClientGatewayBundleFactory.create(context.applicationContext)
    return openCrayLocalRuntimeServerProviders(gatewayBundle)
  }
}
